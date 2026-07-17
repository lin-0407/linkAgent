package com.link.linkagent.creator.interactive.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自研联网搜索工具的本地测试。
 *
 * 普通测试只验证搜索结果解析和正文清洗；实时网络测试默认禁用，由作者排障时显式开启。
 */
class WebSearchToolTest {

    /**
     * 使用项目真实 HTTP 客户端验证当前 JVM 能否访问主搜索源。
     *
     * 默认禁用是为了避免普通单测产生外部网络请求；作者排查网络时通过系统属性显式开启。
     */
    @Test
    @EnabledIfSystemProperty(named = "linkagent.web-search.live", matches = "true")
    void shouldReachAtLeastOneRealSearchSource() {
        WebSearchTool tool = new WebSearchTool(
                "https://cn.bing.com", "https://html.duckduckgo.com");

        String result = tool.execute("Spring AI 1.1.4 官方文档");

        assertThat(result)
                .as("当前 JVM 必须至少能访问一个搜索源，实际结果：" + result)
                .startsWith("联网搜索结果：");
    }

    /** 验证 Bing RSS 可以稳定解析为与 DuckDuckGo 相同的搜索结果结构。 */
    @Test
    void shouldParseBingRssResults() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        var results = tool.parseBingRssResults("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss><channel><item>
                  <title>示例文章</title>
                  <link>https://example.com/article</link>
                  <description>&lt;p&gt;示例摘要&lt;/p&gt;</description>
                </item></channel></rss>
                """);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("示例文章");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/article");
        assertThat(results.get(0).snippet()).contains("示例摘要");
    }

    /** 验证真实解析原因会被保留，避免联网失败时只看到无法定位的统一错误。 */
    @Test
    void shouldRetainBingRssParseFailureCause() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        assertThatThrownBy(() -> tool.parseBingRssResults("<rss><unclosed></rss>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Bing RSS 响应解析失败：")
                .hasMessageContaining("SAXParseException");
    }

    /** 验证兼容运行时 XML Provider 的同时仍禁止 DOCTYPE，避免 RSS 通过外部实体读取本地或内网资源。 */
    @Test
    void shouldRejectDoctypeInBingRss() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);
        String rssWithExternalEntity = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY external SYSTEM "file:///etc/passwd">]>
                <rss><channel><item>
                  <title>&external;</title>
                  <link>https://example.com/article</link>
                </item></channel></rss>
                """;

        assertThatThrownBy(() -> tool.parseBingRssResults(rssWithExternalEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Bing RSS 响应解析失败：");
    }

    /** 验证主搜索源异常时会切换备用源，而不是直接结束整次 web_search。 */
    @Test
    void shouldFallbackWhenPrimarySearchSourceFails() {
        WebSearchTool tool = new WebSearchTool((RestClient) null, List.of(
                new WebSearchTool.SearchSource("primary", query -> {
                    throw new IllegalStateException("连接超时");
                }),
                new WebSearchTool.SearchSource("fallback", query -> List.of(
                        new WebSearchTool.SearchResult("备用结果", "https://example.com/fallback", "备用摘要")
                ))
        ));

        var results = tool.searchWithFallback("测试查询");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("备用结果");
    }

    /** 验证搜索页中的结果链接、标题和摘要可以被提取给后续网页抓取阶段。 */
    @Test
    void shouldParseSearchResultsFromHtml() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        var results = tool.parseSearchResults("""
                <div class="result">
                  <a class="result__a" href="https://example.com/article">示例标题</a>
                  <a class="result__snippet">示例摘要</a>
                </div>
                <div class="result">
                  <a class="result__a" href="https://example.com/article">重复标题</a>
                </div>
                """);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("示例标题");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/article");
        assertThat(results.get(0).snippet()).isEqualTo("示例摘要");
    }

    /** 验证正文清洗会去掉脚本、导航标签，同时保留标题、段落和列表文字。 */
    @Test
    void shouldCleanHtmlAndPreserveReadableLayout() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        String cleaned = tool.cleanHtmlToText("""
                <html><body>
                  <nav>导航内容</nav>
                  <div>推荐内容</div>
                  <article>
                    <h1>文章标题</h1>
                    <p>第一段内容。</p>
                    <ul><li>第一项</li><li>第二项</li></ul>
                    <script>secret()</script>
                  </article>
                </body></html>
                """);

        assertThat(cleaned)
                .contains("文章标题")
                .contains("第一段内容")
                .contains("第一项")
                .doesNotContain("导航内容")
                .doesNotContain("推荐内容")
                .doesNotContain("secret");
    }

    /** 验证响应头缺少 charset 时读取 HTML meta，避免 UTF-8 中文被误解码成乱码。 */
    @Test
    void shouldDecodeUtf8PageFromHtmlMetaCharset() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);
        byte[] body = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"></head><body>什么是购买力平价</body></html>
                """.getBytes(StandardCharsets.UTF_8);

        String decoded = tool.decodePageBody(body, MediaType.TEXT_HTML);

        assertThat(decoded).contains("什么是购买力平价");
    }

    /** 验证空查询在发起 HTTP 请求前被拦截。 */
    @Test
    void shouldRejectBlankQuery() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        assertThat(tool.execute("  "))
                .isEqualTo("联网搜索失败：请提供具体搜索问题。");
    }
}
