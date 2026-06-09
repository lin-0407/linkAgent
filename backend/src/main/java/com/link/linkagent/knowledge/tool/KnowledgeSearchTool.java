package com.link.linkagent.knowledge.tool;

import com.link.linkagent.knowledge.model.ReferenceVideoEvidence;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidenceItem;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchResponse;
import com.link.linkagent.knowledge.service.KnowledgeReferenceRetrievalService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.util.TextUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库案例检索工具（阶段 5.3a）。
 * <p>
 * 把 5.2 已跑通的检索内核 {@link KnowledgeReferenceRetrievalService#search} 包成 Agent 可调用的工具，
 * 让通用 ReAct 内核第一次拥有真实的创作领域能力——这是「创作能力 Tool 化 / 内核统一」的地基刀，
 * 也是后续发布前优化 Agent 化（5.3b）自主调用知识的前提。
 * <p>
 * 为什么放在 knowledge 模块自己的包：能力模块自持其 Agent 工具，只反向依赖最小的 {@link Tool} 契约，
 * 既被 {@code ToolRegistry} 按类型自动收编，又避免玩具工具包 {@code tool.builtin} 反向耦合到领域模块。
 * <p>
 * 为什么用 {@code @ConditionalOnProperty} 门控：RAG 主开关默认关时本工具<b>不存在</b>，
 * 通用 Agent 的工具集与今日字节级一致（零回归）；只有作者显式打开 {@code knowledge.rag.enabled=true}
 * 时工具才现身。开关开后检索内部仍自理向量 / hybrid / SQL 兜底的优雅降级，工具侧无需再判。
 */
@Component
@ConditionalOnProperty(prefix = "knowledge.rag", name = "enabled", havingValue = "true")
public class KnowledgeSearchTool implements Tool {

    /** 工具名进入系统提示词，作为 Agent 的 Action 标识，必须稳定、英文下划线。 */
    private static final String TOOL_NAME = "knowledge_search";

    /** 查询词上限：工具是直接调 search() 而非走 Controller @Valid，校验注解不生效，必须自己兜住 query 的 @Size(500)。 */
    private static final int QUERY_MAX_LENGTH = 500;

    /** 注入 Agent 上下文的案例条数：偏小以控制 Observation 体积，避免撑爆 ReAct 对话历史。 */
    private static final int TOP_K = 5;

    private static final int TITLE_PREVIEW = 60;
    private static final int HIGHLIGHT_PREVIEW = 80;
    private static final int EVIDENCE_PREVIEW = 60;

    private final KnowledgeReferenceRetrievalService retrievalService;

    public KnowledgeSearchTool(KnowledgeReferenceRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        // 描述是 LLM 决定「何时调用」的唯一依据，写成中文且点明输入形态与适用场景。
        return "检索 B 站跨分区优质与竞品视频案例库。输入：一句话查询词（如某个选题方向、表达方式或标题思路）。"
                + "返回同赛道真实案例的标题、分区、层级、关键数据与代表性观众原话，用于需要参考『同类视频怎么做』时。";
    }

    @Override
    public String execute(String input) {
        String query = TextUtil.trimToNull(input);
        if (query == null) {
            return "知识库检索失败：请提供检索关键词（一句话描述选题 / 表达 / 标题方向）。";
        }
        try {
            // category / tier 传 null：案例库尚稀疏，先求召回不做层级收窄；strategy 传 null 走配置默认（REWRITE 查询改写提升召回）。
            ReferenceVideoSearchRequest request = new ReferenceVideoSearchRequest(
                    TextUtil.abbreviate(query, QUERY_MAX_LENGTH), null, null, TOP_K, null);
            ReferenceVideoSearchResponse response = retrievalService.search(request);
            return formatResponse(query, response);
        } catch (Exception ex) {
            // search() 设计上优雅降级不抛异常；此处兜底是为了任何意外都不把 Agent 循环带挂，只回降级文本。
            return "知识库检索暂不可用：" + ex.getMessage();
        }
    }

    /**
     * 把检索响应压成一段可读 Observation 回灌 ReAct 循环。
     * 只取 mode + 卡片要点 + 代表证据，丢弃 enhancedQueries / reranked 等对 Agent 推理无用的回显，控制体积。
     */
    private String formatResponse(String query, ReferenceVideoSearchResponse response) {
        List<ReferenceVideoResponse> items = response.items();
        if (items == null || items.isEmpty()) {
            return "知识库未检索到与「" + query + "」相关的案例。";
        }
        Map<String, ReferenceVideoEvidence> evidenceByVideoId = indexEvidence(response.evidence());

        StringBuilder builder = new StringBuilder();
        builder.append("检索模式：").append(response.mode())
                .append("；命中 ").append(items.size()).append(" 条同赛道案例：\n");
        int index = 1;
        for (ReferenceVideoResponse item : items) {
            builder.append(index++).append(". 《")
                    .append(TextUtil.preview(item.title(), TITLE_PREVIEW, "（无标题）")).append("》")
                    .append("｜分区:").append(TextUtil.trimToDefault(item.category(), "未知"))
                    .append("｜层级:").append(tierLabel(item.tier()));
            appendSignals(builder, item);
            if (TextUtil.hasText(item.highlightSummary())) {
                builder.append("｜亮点:").append(TextUtil.preview(item.highlightSummary(), HIGHLIGHT_PREVIEW, ""));
            }
            builder.append("\n");
            appendEvidence(builder, evidenceByVideoId.get(item.videoId()));
        }
        return builder.toString();
    }

    /** 证据按 videoId 建查找表，供卡片逐条挂上「被哪条观众原话召回」。 */
    private Map<String, ReferenceVideoEvidence> indexEvidence(List<ReferenceVideoEvidence> evidence) {
        Map<String, ReferenceVideoEvidence> map = new HashMap<>();
        if (evidence != null) {
            for (ReferenceVideoEvidence group : evidence) {
                map.put(group.videoId(), group);
            }
        }
        return map;
    }

    /** 只挂最具代表性的一条证据原话即可，多了无益于 Agent 判断且撑大上下文。 */
    private void appendEvidence(StringBuilder builder, ReferenceVideoEvidence group) {
        if (group == null || group.items() == null || group.items().isEmpty()) {
            return;
        }
        ReferenceVideoEvidenceItem first = group.items().get(0);
        if (TextUtil.hasText(first.content())) {
            builder.append("   观众原话：「")
                    .append(TextUtil.preview(first.content(), EVIDENCE_PREVIEW, ""))
                    .append("」\n");
        }
    }

    /** 关键热度信号有则带、无则略，避免给 Agent 喂 null 或 0 的噪声。 */
    private void appendSignals(StringBuilder builder, ReferenceVideoResponse item) {
        if (item.viewCount() != null) {
            builder.append("｜播放:").append(item.viewCount());
        }
        if (item.likeCount() != null) {
            builder.append("｜点赞:").append(item.likeCount());
        }
    }

    /** 层级英文枚举对 Agent 不直观，转中文并保留原值便于回溯。 */
    private String tierLabel(String tier) {
        if (tier == null) {
            return "未知";
        }
        return switch (tier) {
            case "BENCHMARK" -> "优品(BENCHMARK)";
            case "COMPETITOR" -> "竞品(COMPETITOR)";
            case "OWN_HISTORY" -> "自历史(OWN_HISTORY)";
            default -> tier;
        };
    }
}
