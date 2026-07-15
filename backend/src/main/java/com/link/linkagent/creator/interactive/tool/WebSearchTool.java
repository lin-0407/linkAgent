package com.link.linkagent.creator.interactive.tool;

import com.link.linkagent.tool.Tool;
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

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 面向 ReAct Agent 的无密钥联网搜索工具。
 *
 * 工具先请求公开搜索页，再抓取少量公开结果页面，并使用 JDK HTML Parser 清除脚本、导航和表单等噪声；
 * 这样 Agent 看到的是带来源 URL 的正文片段，而不是搜索页源码。工具默认关闭，只有作者显式开启后才注册。
 */
@Component
@ConditionalOnProperty(name = "agent.tool.web-search.enabled", havingValue = "true")
public class WebSearchTool implements Tool {

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

    private final RestClient restClient;

    /** Spring 生产构造器：搜索地址可替换为自建的兼容搜索入口。 */
    @Autowired
    public WebSearchTool(
            @Value("${agent.tool.web-search.base-url:https://html.duckduckgo.com}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(defaultRequestFactory()).build());
    }

    /** 可注入 HTTP 客户端的构造器，供不启动 Spring 容器的测试复用解析逻辑。 */
    public WebSearchTool(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 为搜索页和目标网页设置统一连接/读取超时，避免一次搜索长期占用 ReAct 线程。 */
    private static SimpleClientHttpRequestFactory defaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        requestFactory.setReadTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        return requestFactory;
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
            String searchHtml = requestSearchPage(TextUtil.abbreviate(query, QUERY_MAX_LENGTH));
            List<SearchResult> results = parseSearchResults(searchHtml);
            if (results.isEmpty()) {
                return "联网搜索未找到公开网页结果。";
            }
            return fetchAndFormatPages(results);
        } catch (Exception exception) {
            return "联网搜索失败：" + resolveErrorMessage(exception);
        }
    }

    /** 请求搜索页面时只使用不包含用户身份的常见浏览器头，不转发用户 Cookie。 */
    private String requestSearchPage(String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/html/").queryParam("q", query).build())
                .headers(this::applyBrowserLikeHeaders)
                .retrieve()
                .body(String.class);
    }

    /** 从搜索 HTML 提取前几条结果，避免把整个搜索页面塞进 Agent 上下文。 */
    List<SearchResult> parseSearchResults(String html) {
        SearchResultCollector collector = new SearchResultCollector();
        parseHtml(html, collector);
        return collector.results.stream()
                .filter(result -> TextUtil.hasText(result.url()))
                .distinct()
                .limit(MAX_SEARCH_RESULTS)
                .toList();
    }

    /** 抓取公开结果页面，并在每个页面完成正文清洗后再拼接 Observation。 */
    private String fetchAndFormatPages(List<SearchResult> results) {
        StringBuilder output = new StringBuilder("联网搜索结果：\n");
        int index = 1;
        for (SearchResult result : results) {
            output.append(index++).append(". ").append(result.title()).append("\n")
                    .append("   来源：").append(result.url()).append("\n");
            try {
                String pageHtml = fetchPublicPage(result.url());
                String cleanedText = cleanHtmlToText(pageHtml);
                if (TextUtil.hasText(cleanedText)) {
                    output.append("   正文：").append(TextUtil.abbreviate(cleanedText, PAGE_MAX_LENGTH)).append("\n");
                } else if (TextUtil.hasText(result.snippet())) {
                    output.append("   搜索摘要：").append(result.snippet()).append("\n");
                }
            } catch (Exception exception) {
                // 单个网页不可访问时保留搜索摘要，避免一个站点失败导致整个搜索 Observation 丢失。
                if (TextUtil.hasText(result.snippet())) {
                    output.append("   搜索摘要：").append(result.snippet()).append("\n");
                }
            }
            if (output.length() >= MAX_RESPONSE_LENGTH) {
                break;
            }
        }
        return TextUtil.abbreviate(output.toString().trim(), MAX_RESPONSE_LENGTH);
    }

    /** 只允许公开 HTTP(S) 页面，拒绝 localhost、环回地址、内网地址和非 HTTP 协议。 */
    private String fetchPublicPage(String rawUrl) {
        URI uri = resolvePublicUri(rawUrl);
        ResponseEntity<String> response = restClient.get()
                .uri(uri)
                .headers(this::applyBrowserLikeHeaders)
                .retrieve()
                .toEntity(String.class);
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null
                && !MediaType.TEXT_HTML.isCompatibleWith(contentType)
                && !MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            throw new IllegalArgumentException("搜索结果不是 HTML 或纯文本页面");
        }
        String body = response.getBody();
        if (body != null && body.length() > PAGE_DOWNLOAD_MAX_LENGTH) {
            return body.substring(0, PAGE_DOWNLOAD_MAX_LENGTH);
        }
        return body;
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
        headers.set(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE + ",application/xhtml+xml");
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

    /** 搜索结果的最小结构，只保留 Agent 后续推理需要的字段。 */
    record SearchResult(String title, String url, String snippet) {
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
