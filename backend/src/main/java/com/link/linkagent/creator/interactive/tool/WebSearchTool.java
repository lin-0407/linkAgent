package com.link.linkagent.creator.interactive.tool;

import com.link.linkagent.tool.Tool;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向 ReAct Agent 的无密钥联网搜索工具。
 *
 * 工具先请求公开搜索页，再抓取少量公开结果页面，并使用 JDK HTML Parser 清除脚本、导航和表单等噪声；
 * 这样 Agent 看到的是带来源 URL 的正文片段，而不是搜索页源码。工具默认关闭，只有作者显式开启后才注册。
 */
@Component
@ConditionalOnProperty(name = "agent.tool.web-search.enabled", havingValue = "true")
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TOOL_NAME = "web_search";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final int QUERY_MAX_LENGTH = 500;
    private static final int MAX_SEARCH_RESULTS = 3;
    private static final int PAGE_MAX_LENGTH = 60000;
    private static final int PAGE_DOWNLOAD_MAX_LENGTH = 1_000_000;
    private static final int MAX_RESPONSE_LENGTH = 60000;
    private static final int REQUEST_TIMEOUT_SECONDS = 12;
    private static final int CHARSET_SNIFF_LENGTH = 8192;
    private static final int LONG_PAGE_COMPRESSION_THRESHOLD = 2000;
    private static final int COMPRESSION_INPUT_MAX_LENGTH = 24000;
    private static final int COMPRESSION_OUTPUT_MAX_LENGTH = 6000;
    private static final String COMPRESSION_SYSTEM_PROMPT = """
            你是联网资料压缩助手。请只压缩输入中的网页正文，不补充外部知识，不推测原文没有的信息。
            必须按来源编号分别总结，保留与搜索问题相关的事实、日期、数字、案例、结论和不确定性。
            删除导航、样式、重复段落和无关内容。每个来源控制在 1000 字以内，并保留“来源编号：N”标题。
            """;
    private static final Pattern HTML_META_CHARSET_PATTERN = Pattern.compile(
            "(?i)<meta\\b[^>]*\\bcharset\\s*=\\s*[\\\"']?\\s*([^\\s\\\"'/>;]+)");

    private final RestClient pageClient;
    private final List<SearchSource> searchSources;
    private final ContentCompressor contentCompressor;

    /** Spring 生产构造器：Bing RSS 为主源，DuckDuckGo HTML 为备用源。 */
    @Autowired
    public WebSearchTool(
            @Value("${agent.tool.web-search.bing-base-url:https://cn.bing.com}") String bingBaseUrl,
            @Value("${agent.tool.web-search.duckduckgo-base-url:https://html.duckduckgo.com}") String duckDuckGoBaseUrl,
            @Value("${agent.tool.web-search.compression-enabled:true}") boolean compressionEnabled,
            @Value("${agent.tool.web-search.compression-model:${LLM_MODEL}}") String compressionModel,
            LLMService llmService) {
        this(bingBaseUrl, duckDuckGoBaseUrl,
                compressionEnabled
                        ? (systemPrompt, userMessage) -> llmService.chatWithModel(
                                compressionModel, systemPrompt, userMessage)
                        : null);
    }

    /** 不启用正文压缩的构造器，供只验证网络连通性和解析逻辑的测试使用。 */
    public WebSearchTool(String bingBaseUrl, String duckDuckGoBaseUrl) {
        this(bingBaseUrl, duckDuckGoBaseUrl, null);
    }

    /** 可注入轻量模型调用函数的构造器，供独立评测在不启动 Spring 容器时启用正文压缩。 */
    public WebSearchTool(String bingBaseUrl, String duckDuckGoBaseUrl, ContentCompressor contentCompressor) {
        RestClient bingClient = buildClient(bingBaseUrl);
        RestClient duckDuckGoClient = buildClient(duckDuckGoBaseUrl);
        this.pageClient = buildClient(null);
        this.searchSources = List.of(
                new SearchSource("bing-rss", query -> requestBingResults(bingClient, query)),
                new SearchSource("duckduckgo-html", query -> requestDuckDuckGoResults(duckDuckGoClient, query))
        );
        this.contentCompressor = contentCompressor;
    }

    /** 可注入 HTTP 客户端的构造器，供不启动 Spring 容器的测试复用解析逻辑。 */
    public WebSearchTool(RestClient restClient) {
        this.pageClient = restClient;
        this.searchSources = List.of();
        this.contentCompressor = null;
    }

    /** 可注入搜索源的构造器，供本地测试验证主源失败后的回退顺序。 */
    WebSearchTool(RestClient pageClient, List<SearchSource> searchSources) {
        this(pageClient, searchSources, null);
    }

    /** 可同时注入搜索源和压缩函数的构造器，用于不访问真实网络的压缩流程测试。 */
    WebSearchTool(RestClient pageClient, List<SearchSource> searchSources, ContentCompressor contentCompressor) {
        this.pageClient = pageClient;
        this.searchSources = List.copyOf(searchSources);
        this.contentCompressor = contentCompressor;
    }

    /** 为搜索页和目标网页设置统一连接/读取超时，避免一次搜索长期占用 ReAct 线程。 */
    private static SimpleClientHttpRequestFactory defaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        requestFactory.setReadTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        return requestFactory;
    }

    /** 创建统一超时的 HTTP 客户端；baseUrl 为空时用于访问搜索结果中的绝对 URL。 */
    private static RestClient buildClient(String baseUrl) {
        RestClient.Builder builder = RestClient.builder().requestFactory(defaultRequestFactory());
        return TextUtil.hasText(baseUrl) ? builder.baseUrl(baseUrl).build() : builder.build();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "联网搜索公开网页并提取正文，用于补充模型知识库之外的时效性信息。"
                + "输入：一条具体搜索问题；输出：搜索结果标题、来源 URL 和清洗后的网页正文。"
                + "只读取公开 http/https 页面，不执行网页操作。";
    }

    @Override
    public String execute(String input) {
        String query = TextUtil.trimToNull(input);
        if (query == null) {
            return "联网搜索失败：请提供具体搜索问题。";
        }
        try {
            List<SearchResult> results = searchWithFallback(TextUtil.abbreviate(query, QUERY_MAX_LENGTH));
            return fetchAndFormatPages(results);
        } catch (Exception exception) {
            return "联网搜索失败：" + resolveErrorMessage(exception);
        }
    }

    /** 按配置顺序尝试搜索源，超时、请求失败或零结果都会自动切换到下一个源。 */
    List<SearchResult> searchWithFallback(String query) {
        List<String> failures = new ArrayList<>();
        for (SearchSource source : searchSources) {
            try {
                List<SearchResult> results = normalizeSearchResults(source.search().apply(query));
                if (!results.isEmpty()) {
                    log.info("联网搜索源调用成功，source={}, resultCount={}", source.name(), results.size());
                    return results;
                }
                failures.add(source.name() + "=零结果");
                log.warn("联网搜索源未返回结果，source={}", source.name());
            } catch (RuntimeException exception) {
                String error = resolveErrorMessage(exception);
                failures.add(source.name() + "=" + error);
                log.warn("联网搜索源调用失败，source={}, error={}", source.name(), error);
            }
        }
        throw new IllegalStateException("所有联网搜索源均不可用：" + String.join("；", failures));
    }

    /** 请求 DuckDuckGo 搜索页面时只使用不包含用户身份的常见浏览器头。 */
    private List<SearchResult> requestDuckDuckGoResults(RestClient client, String query) {
        String searchHtml = client.get()
                .uri(uriBuilder -> uriBuilder.path("/html/").queryParam("q", query).build())
                .headers(this::applyBrowserLikeHeaders)
                .retrieve()
                .body(String.class);
        return parseSearchResults(searchHtml);
    }

    /** Bing 的 RSS 输出结构稳定且无需解析搜索页脚本，作为默认主搜索源。 */
    private List<SearchResult> requestBingResults(RestClient client, String query) {
        String rss = client.get()
                .uri(uriBuilder -> uriBuilder.path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "rss")
                        .queryParam("setlang", "zh-cn")
                        .build())
                .headers(this::applyBrowserLikeHeaders)
                .retrieve()
                .body(String.class);
        return parseBingRssResults(rss);
    }

    /** 使用禁用外部实体的 XML 解析器读取 Bing RSS，避免用字符串规则拆 XML。 */
    List<SearchResult> parseBingRssResults(String rss) {
        if (TextUtil.isBlank(rss)) {
            return List.of();
        }
        Document document = parseBingRssDocument(rss);
        NodeList items = document.getElementsByTagName("item");
        List<SearchResult> results = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            String title = childText(item, "title");
            String url = childText(item, "link");
            String snippet = cleanHtmlToText(childText(item, "description"));
            if (TextUtil.hasText(url)) {
                results.add(new SearchResult(title, url, snippet));
            }
        }
        return results;
    }

    /** 单独解析 RSS 文档，以便区分 XML 解析失败和后续摘要清洗失败。 */
    private Document parseBingRssDocument(String rss) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // 当前运行时 XML Provider 不支持 ACCESS_EXTERNAL_* 属性；直接禁止 DOCTYPE 同样阻断外部实体声明。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(rss)));
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Bing RSS 响应解析失败：" + describeException(exception), exception);
        }
    }

    /** 读取 RSS item 的直接子字段；不存在时返回空字符串，交由后续过滤。 */
    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : TextUtil.trimToDefault(nodes.item(0).getTextContent(), "");
    }

    /** 搜索源结果统一去重和限量，保证故障转移前后输出契约一致。 */
    private List<SearchResult> normalizeSearchResults(List<SearchResult> results) {
        if (results == null) {
            return List.of();
        }
        Map<String, SearchResult> uniqueByUrl = new LinkedHashMap<>();
        for (SearchResult result : results) {
            if (result != null && TextUtil.hasText(result.url())) {
                uniqueByUrl.putIfAbsent(result.url(), result);
            }
        }
        return uniqueByUrl.values().stream().limit(MAX_SEARCH_RESULTS).toList();
    }

    /** 从搜索 HTML 提取前几条结果，避免把整个搜索页面塞进 Agent 上下文。 */
    List<SearchResult> parseSearchResults(String html) {
        SearchResultCollector collector = new SearchResultCollector();
        parseHtml(html, collector);
        return normalizeSearchResults(collector.results);
    }

    /** 抓取公开结果页面，并在每个页面完成正文清洗后再拼接 Observation。 */
    private String fetchAndFormatPages(List<SearchResult> results) {
        List<FetchedPage> pages = new ArrayList<>();
        for (SearchResult result : results) {
            try {
                String pageHtml = fetchPublicPage(result.url());
                String cleanedText = cleanHtmlToText(pageHtml);
                if (TextUtil.hasText(cleanedText)) {
                    pages.add(new FetchedPage(result, TextUtil.abbreviate(cleanedText, PAGE_MAX_LENGTH), true));
                } else if (TextUtil.hasText(result.snippet())) {
                    pages.add(new FetchedPage(result, result.snippet(), false));
                } else {
                    pages.add(new FetchedPage(result, "", false));
                }
            } catch (Exception exception) {
                // 单个网页不可访问时保留搜索摘要，避免一个站点失败导致整个搜索 Observation 丢失。
                pages.add(new FetchedPage(result, TextUtil.trimToDefault(result.snippet(), ""), false));
            }
        }
        return formatFetchedPages(pages);
    }

    /**
     * 将已抓取页面格式化为 Observation；多个长页面合并为一次轻量模型压缩调用。
     *
     * 压缩只替换超过阈值的完整正文，搜索摘要和短页面保持原文，避免对本就精简的信息再次改写。
     */
    String formatFetchedPages(List<FetchedPage> pages) {
        List<Integer> longPageIndexes = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            FetchedPage page = pages.get(index);
            if (page.fullPage() && page.content().length() > LONG_PAGE_COMPRESSION_THRESHOLD) {
                longPageIndexes.add(index);
            }
        }
        String compressedContent = compressLongPages(pages, longPageIndexes);
        boolean compressionSucceeded = TextUtil.hasText(compressedContent);
        StringBuilder output = new StringBuilder("联网搜索结果：\n");
        for (int index = 0; index < pages.size(); index++) {
            FetchedPage page = pages.get(index);
            output.append(index + 1).append(". ").append(page.result().title()).append("\n")
                    .append("   来源：").append(page.result().url()).append("\n");
            boolean compressedLongPage = compressionSucceeded && longPageIndexes.contains(index);
            if (!compressedLongPage && TextUtil.hasText(page.content())) {
                output.append(page.fullPage() ? "   正文：" : "   搜索摘要：")
                        .append(page.content()).append("\n");
            }
        }
        if (compressionSucceeded) {
            output.append("长页面压缩摘要（来源编号对应上方搜索结果）：\n")
                    .append(TextUtil.abbreviate(compressedContent, COMPRESSION_OUTPUT_MAX_LENGTH)).append("\n");
        }
        return TextUtil.abbreviate(output.toString().trim(), MAX_RESPONSE_LENGTH);
    }

    /** 一次性压缩所有长页面；模型失败时返回空字符串，由调用方自动保留原正文。 */
    private String compressLongPages(List<FetchedPage> pages, List<Integer> longPageIndexes) {
        if (contentCompressor == null || longPageIndexes.isEmpty()) {
            return null;
        }
        int perPageInputLimit = Math.max(1, COMPRESSION_INPUT_MAX_LENGTH / longPageIndexes.size());
        StringBuilder input = new StringBuilder("搜索到的长页面如下，请按来源编号分别压缩：\n\n");
        for (Integer pageIndex : longPageIndexes) {
            FetchedPage page = pages.get(pageIndex);
            input.append("<source id=\"").append(pageIndex + 1).append("\">\n")
                    .append("标题：").append(page.result().title()).append("\n")
                    .append("URL：").append(page.result().url()).append("\n")
                    .append("正文：\n")
                    .append(TextUtil.abbreviate(page.content(), perPageInputLimit)).append("\n")
                    .append("</source>\n\n");
        }
        try {
            log.info("联网搜索长正文压缩开始，pageCount={}, inputChars={}",
                    longPageIndexes.size(), input.length());
            String compressed = contentCompressor.compress(COMPRESSION_SYSTEM_PROMPT, input.toString());
            if (TextUtil.isBlank(compressed)) {
                log.warn("联网搜索长正文压缩返回空内容，已回退原正文");
                return null;
            }
            log.info("联网搜索长正文压缩完成，pageCount={}, outputChars={}，完整输出：\n{}",
                    longPageIndexes.size(), compressed.length(), compressed);
            return compressed;
        } catch (RuntimeException exception) {
            log.warn("联网搜索长正文压缩失败，已回退原正文，error={}", resolveErrorMessage(exception));
            return null;
        }
    }

    /** 只允许公开 HTTP(S) 页面，拒绝 localhost、环回地址、内网地址和非 HTTP 协议。 */
    private String fetchPublicPage(String rawUrl) {
        URI uri = resolvePublicUri(rawUrl);
        ResponseEntity<byte[]> response = pageClient.get()
                .uri(uri)
                .headers(this::applyBrowserLikeHeaders)
                .retrieve()
                .toEntity(byte[].class);
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null
                && !MediaType.TEXT_HTML.isCompatibleWith(contentType)
                && !MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            throw new IllegalArgumentException("搜索结果不是 HTML 或纯文本页面");
        }
        return decodePageBody(response.getBody(), contentType);
    }

    /** 先按响应头、再按 HTML meta 识别编码，避免无 charset 响应被错误地按 ISO-8859-1 解码。 */
    String decodePageBody(byte[] body, MediaType contentType) {
        if (body == null) {
            return null;
        }
        int bodyLength = Math.min(body.length, PAGE_DOWNLOAD_MAX_LENGTH);
        Charset charset = resolvePageCharset(body, bodyLength, contentType);
        return new String(body, 0, bodyLength, charset);
    }

    /** HTTP 响应头优先级高于页面声明；两者都缺失时按现代网页通用的 UTF-8 处理。 */
    private Charset resolvePageCharset(byte[] body, int bodyLength, MediaType contentType) {
        Charset responseCharset = contentType == null ? null : contentType.getCharset();
        if (responseCharset != null) {
            return responseCharset;
        }
        int sniffLength = Math.min(bodyLength, CHARSET_SNIFF_LENGTH);
        String htmlPrefix = new String(body, 0, sniffLength, StandardCharsets.ISO_8859_1);
        Matcher matcher = HTML_META_CHARSET_PATTERN.matcher(htmlPrefix);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (IllegalArgumentException ignored) {
                // 网页声明未知字符集时回退 UTF-8，避免单个错误 meta 让整页抓取失败。
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 校验 URL 并解析搜索引擎的跳转链接。 */
    private URI resolvePublicUri(String rawUrl) {
        String decodedUrl = decodeRedirectUrl(rawUrl);
        URI uri = URI.create(decodedUrl);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || isPrivateHost(uri.getHost())) {
            throw new IllegalArgumentException("搜索结果不是允许访问的公开 HTTP(S) 地址");
        }
        return uri;
    }

    /** 解析 DuckDuckGo /l/?uddg=... 形式的结果跳转地址。 */
    private String decodeRedirectUrl(String rawUrl) {
        URI uri = URI.create(rawUrl);
        String query = uri.getRawQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                String[] parts = parameter.split("=", 2);
                if (parts.length == 2 && "uddg".equals(parts[0])) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
        }
        return rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl;
    }

    /** 解析 HTML 时保留段落、标题和列表的换行，删除脚本、导航、表单等不适合模型阅读的区域。 */
    String cleanHtmlToText(String html) {
        ContentRootDetector detector = new ContentRootDetector();
        parseHtml(html, detector);
        TextCleaner cleaner = new TextCleaner(detector.hasContentRoot);
        parseHtml(html, cleaner);
        return cleaner.text.toString().replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private void parseHtml(String html, HTMLEditorKit.ParserCallback callback) {
        if (TextUtil.isBlank(html)) {
            return;
        }
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException exception) {
            throw new IllegalArgumentException("网页 HTML 解析失败", exception);
        }
    }

    /** 设置通用请求头，不包含 Cookie、Authorization 等用户身份信息。 */
    private void applyBrowserLikeHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT);
        headers.set(HttpHeaders.ACCEPT,
                MediaType.TEXT_HTML_VALUE + ",application/xhtml+xml,application/rss+xml,application/xml,text/plain");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");
        headers.set(HttpHeaders.CONNECTION, "close");
    }

    /** 解析域名并拒绝明显的本机或内网地址，降低 SSRF 风险。 */
    private boolean isPrivateHost(String host) {
        String normalizedHost = host.toLowerCase();
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".local")) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    return true;
                }
            }
        } catch (IOException exception) {
            return true;
        }
        return false;
    }

    /** 优先返回服务端错误信息，避免把整段异常堆栈塞进 Observation 干扰 Agent。 */
    private String resolveErrorMessage(Exception exception) {
        String message = exception.getMessage();
        return TextUtil.isBlank(message) ? exception.getClass().getSimpleName() : message;
    }

    /** 保留最底层异常类型和消息，让外部响应解析问题可以依据真实原因继续排查。 */
    private String describeException(Exception exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        return rootCause.getClass().getSimpleName()
                + (TextUtil.isBlank(message) ? "" : "：" + message);
    }

    /** 搜索结果的最小结构，只保留 Agent 后续推理需要的字段。 */
    record SearchResult(String title, String url, String snippet) {
    }

    /** 一个可命名的搜索源；名称只用于可用性日志，不包含搜索词。 */
    record SearchSource(String name, Function<String, List<SearchResult>> search) {
    }

    /** 已抓取页面的最小结构，用 fullPage 区分完整正文和搜索引擎摘要。 */
    record FetchedPage(SearchResult result, String content, boolean fullPage) {
    }

    /** 隔离具体模型客户端，让生产环境和独立评测复用同一套压缩流程。 */
    @FunctionalInterface
    public interface ContentCompressor {
        String compress(String systemPrompt, String userMessage);
    }

    /** 从 DuckDuckGo 搜索 HTML 中提取 result__a 和 result__snippet 文本。 */
    private static final class SearchResultCollector extends HTMLEditorKit.ParserCallback {
        private final List<SearchResult> results = new ArrayList<>();
        private String currentUrl;
        private StringBuilder currentTitle;
        private boolean inSnippet;
        private final StringBuilder snippetBuilder = new StringBuilder();

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            String cssClass = String.valueOf(attributes.getAttribute(HTML.Attribute.CLASS));
            if (HTML.Tag.A.equals(tag) && cssClass.contains("result__a")) {
                currentUrl = String.valueOf(attributes.getAttribute(HTML.Attribute.HREF));
                currentTitle = new StringBuilder();
            } else if (HTML.Tag.A.equals(tag) && cssClass.contains("result__snippet")) {
                inSnippet = true;
                snippetBuilder.setLength(0);
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (HTML.Tag.A.equals(tag) && currentTitle != null) {
                if (TextUtil.hasText(currentUrl)) {
                    results.add(new SearchResult(currentTitle.toString().trim(), currentUrl, ""));
                }
                currentUrl = null;
                currentTitle = null;
            }
            if (HTML.Tag.A.equals(tag)) {
                if (inSnippet && !results.isEmpty()) {
                    SearchResult last = results.remove(results.size() - 1);
                    results.add(new SearchResult(last.title(), last.url(),
                            TextUtil.trimToDefault(snippetBuilder.toString(), "")));
                }
                inSnippet = false;
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (currentTitle != null) {
                currentTitle.append(data);
            }
            if (inSnippet) {
                snippetBuilder.append(data);
            }
        }
    }

    /** 先扫描页面是否存在正文容器，避免在正文清洗阶段把导航和推荐区一并保留。 */
    private static final class ContentRootDetector extends HTMLEditorKit.ParserCallback {
        private boolean hasContentRoot;

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            String tagName = tag.toString().toLowerCase();
            if ("main".equals(tagName) || "article".equals(tagName)) {
                hasContentRoot = true;
            }
        }
    }

    /** HTML 正文清洗回调：保留标题、段落和列表的文字层级。 */
    private static final class TextCleaner extends HTMLEditorKit.ParserCallback {
        private final StringBuilder text = new StringBuilder();
        private final boolean onlyContentRoot;
        private int contentRootDepth;
        private int ignoredDepth;

        private TextCleaner(boolean onlyContentRoot) {
            this.onlyContentRoot = onlyContentRoot;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (onlyContentRoot && isContentRoot(tag)) {
                contentRootDepth++;
                return;
            }
            if (onlyContentRoot && contentRootDepth == 0) {
                return;
            }
            if (isIgnoredTag(tag)) {
                ignoredDepth++;
                return;
            }
            if (ignoredDepth > 0) {
                return;
            }
            if (HTML.Tag.LI.equals(tag)) {
                text.append("\n- ");
            } else if (isBlockTag(tag)) {
                text.append("\n");
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (onlyContentRoot && isContentRoot(tag)) {
                contentRootDepth = Math.max(0, contentRootDepth - 1);
                return;
            }
            if (onlyContentRoot && contentRootDepth == 0) {
                return;
            }
            if (isIgnoredTag(tag)) {
                ignoredDepth = Math.max(0, ignoredDepth - 1);
                return;
            }
            if (ignoredDepth == 0 && isBlockTag(tag)) {
                text.append("\n");
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if ((!onlyContentRoot || contentRootDepth > 0)
                    && ignoredDepth == 0 && HTML.Tag.BR.equals(tag)) {
                text.append("\n");
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if ((!onlyContentRoot || contentRootDepth > 0) && ignoredDepth == 0) {
                text.append(data);
            }
        }

        private boolean isIgnoredTag(HTML.Tag tag) {
            String tagName = tag.toString().toLowerCase();
            return switch (tagName) {
                case "head", "script", "style", "noscript", "svg", "nav", "header", "footer", "form", "aside", "iframe" -> true;
                default -> false;
            };
        }

        private boolean isContentRoot(HTML.Tag tag) {
            String tagName = tag.toString().toLowerCase();
            return "main".equals(tagName) || "article".equals(tagName);
        }

        private boolean isBlockTag(HTML.Tag tag) {
            return switch (tag.toString().toLowerCase()) {
                case "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote" -> true;
                default -> false;
            };
        }
    }
}
