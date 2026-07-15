package com.link.linkagent.creator.interactive.tool;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自研联网搜索工具的本地测试。
 *
 * 这里只验证搜索结果解析和正文清洗，不发起真实网络请求；真实连通性由作者手动执行评测验证。
 */
class WebSearchToolTest {

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

    /** 验证空查询在发起 HTTP 请求前被拦截。 */
    @Test
    void shouldRejectBlankQuery() {
        WebSearchTool tool = new WebSearchTool((RestClient) null);

        assertThat(tool.execute("  "))
                .isEqualTo("联网搜索失败：请提供具体搜索问题。");
    }
}
