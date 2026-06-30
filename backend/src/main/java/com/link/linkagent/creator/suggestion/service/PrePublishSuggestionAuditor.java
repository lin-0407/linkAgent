package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.PrePublishAuditIssue;
import com.link.linkagent.creator.suggestion.model.PrePublishAuditReport;
import com.link.linkagent.creator.suggestion.model.PrePublishEvidenceRef;
import com.link.linkagent.util.TextUtil;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 发布前优化建议审查器。
 * <p>
 * 这里选择确定性规则而不是再开一次 LLM 审稿，是因为第一阶段要先把最容易误导 UP 主的
 * 问题稳定暴露出来：证据编号编造、标题无依据、HIGH 优先级动作无依据、夸大收益承诺。
 */
@Component
public class PrePublishSuggestionAuditor {

    private static final int INITIAL_SCORE = 100;
    private static final int ERROR_DEDUCT_SCORE = 25;
    private static final int WARN_DEDUCT_SCORE = 10;
    private static final Set<String> RISKY_PHRASES = Set.of(
            "全网最强",
            "必爆",
            "爆款保证",
            "涨粉",
            "完播率翻倍",
            "推荐算法会",
            "平台一定",
            "稳赚",
            "一定火"
    );

    private final ObjectMapper objectMapper;

    public PrePublishSuggestionAuditor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PrePublishAuditReport audit(CreatorSuggestionRecord record, List<PrePublishEvidenceRef> evidenceRefs) {
        List<PrePublishAuditIssue> issues = new ArrayList<>();
        Set<String> validEvidenceIds = validEvidenceIds(evidenceRefs);
        if (!"PARSED".equals(record.getParseStatus())) {
            issues.add(new PrePublishAuditIssue(
                    "WARN",
                    "RAW_ONLY_OUTPUT",
                    "parseStatus",
                    "建议没有解析成结构化 JSON，只能展示原文，无法做完整审查。",
                    List.of()
            ));
            return buildReport("AUDIT_SKIPPED", 60, issues);
        }

        auditTitleSuggestions(record.getTitleSuggestions(), validEvidenceIds, issues);
        auditActionableRevisionPlan(record.getActionableRevisionPlan(), validEvidenceIds, issues);
        auditRiskyPhrases(record, issues);
        return buildReport(resolveStatus(issues), calculateScore(issues), issues);
    }

    private void auditTitleSuggestions(String titleSuggestions,
                                       Set<String> validEvidenceIds,
                                       List<PrePublishAuditIssue> issues) {
        JsonNode rootNode = readArray(titleSuggestions, "titleSuggestions", issues);
        if (rootNode == null) {
            return;
        }
        if (rootNode.isEmpty()) {
            issues.add(new PrePublishAuditIssue(
                    "WARN",
                    "MISSING_TITLE_SUGGESTION",
                    "titleSuggestions",
                    "缺少标题建议，发布前优化结果无法直接帮助作者改标题。",
                    List.of()
            ));
            return;
        }
        for (int index = 0; index < rootNode.size(); index++) {
            JsonNode itemNode = rootNode.get(index);
            String target = "titleSuggestions[" + index + "]";
            List<String> evidenceIds = extractEvidenceIds(itemNode);
            auditEvidenceIds(target, evidenceIds, validEvidenceIds, issues);
            if (evidenceIds.isEmpty()) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "TITLE_WITHOUT_EVIDENCE",
                        target,
                        "标题建议没有引用证据，作者难以判断这条标题为什么更适合当前内容。",
                        List.of()
                ));
            }
            if (!TextUtil.hasText(text(itemNode, "title"))) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "EMPTY_TITLE_TEXT",
                        target,
                        "标题建议缺少 title 字段，前端无法稳定展示可直接使用的标题。",
                        evidenceIds
                ));
            }
        }
    }

    private void auditActionableRevisionPlan(String actionableRevisionPlan,
                                             Set<String> validEvidenceIds,
                                             List<PrePublishAuditIssue> issues) {
        JsonNode rootNode = readArray(actionableRevisionPlan, "actionableRevisionPlan", issues);
        if (rootNode == null) {
            return;
        }
        if (rootNode.isEmpty()) {
            issues.add(new PrePublishAuditIssue(
                    "WARN",
                    "MISSING_ACTION_PLAN",
                    "actionableRevisionPlan",
                    "缺少可执行修改计划，建议会停留在方向描述，作者不知道下一步具体改哪里。",
                    List.of()
            ));
            return;
        }
        for (int index = 0; index < rootNode.size(); index++) {
            JsonNode itemNode = rootNode.get(index);
            String target = "actionableRevisionPlan[" + index + "]";
            List<String> evidenceIds = extractEvidenceIds(itemNode);
            auditEvidenceIds(target, evidenceIds, validEvidenceIds, issues);
            if ("HIGH".equalsIgnoreCase(text(itemNode, "priority")) && evidenceIds.isEmpty()) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "HIGH_ACTION_WITHOUT_EVIDENCE",
                        target,
                        "HIGH 优先级修改动作没有引用证据，容易把模型猜测误当成必须执行的改法。",
                        List.of()
                ));
            }
            if (!TextUtil.hasText(text(itemNode, "action"))) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "EMPTY_ACTION_TEXT",
                        target,
                        "修改计划缺少 action 字段，作者无法直接执行。",
                        evidenceIds
                ));
            }
        }
    }

    private void auditEvidenceIds(String target,
                                  List<String> evidenceIds,
                                  Set<String> validEvidenceIds,
                                  List<PrePublishAuditIssue> issues) {
        List<String> invalidIds = evidenceIds.stream()
                .filter(evidenceId -> !validEvidenceIds.contains(evidenceId))
                .toList();
        if (!invalidIds.isEmpty()) {
            issues.add(new PrePublishAuditIssue(
                    "ERROR",
                    "INVALID_EVIDENCE_ID",
                    target,
                    "建议引用了不存在的证据编号，说明模型可能编造了依据。",
                    invalidIds
            ));
        }
    }

    private void auditRiskyPhrases(CreatorSuggestionRecord record, List<PrePublishAuditIssue> issues) {
        String mergedText = String.join("\n",
                TextUtil.trimToDefault(record.getContentPositioning(), ""),
                TextUtil.trimToDefault(record.getRiskPoints(), ""),
                TextUtil.trimToDefault(record.getTitleSuggestions(), ""),
                TextUtil.trimToDefault(record.getDescriptionSuggestion(), ""),
                TextUtil.trimToDefault(record.getActionableRevisionPlan(), "")
        );
        String lowerText = mergedText.toLowerCase(Locale.ROOT);
        for (String phrase : RISKY_PHRASES) {
            if (lowerText.contains(phrase.toLowerCase(Locale.ROOT))) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "RISKY_PROMISE_PHRASE",
                        "suggestionText",
                        "建议中出现可能误导作者的夸大表达：" + phrase,
                        List.of()
                ));
            }
        }
    }

    private JsonNode readArray(String json, String target, List<PrePublishAuditIssue> issues) {
        if (!TextUtil.hasText(json)) {
            issues.add(new PrePublishAuditIssue(
                    "WARN",
                    "MISSING_FIELD",
                    target,
                    "结构化建议缺少 " + target + " 字段，审查无法覆盖该部分。",
                    List.of()
            ));
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(json);
            if (!rootNode.isArray()) {
                issues.add(new PrePublishAuditIssue(
                        "WARN",
                        "FIELD_NOT_ARRAY",
                        target,
                        target + " 不是 JSON 数组，前端展示和规则审查都可能不稳定。",
                        List.of()
                ));
                return null;
            }
            return rootNode;
        } catch (JsonProcessingException exception) {
            issues.add(new PrePublishAuditIssue(
                    "WARN",
                    "FIELD_JSON_INVALID",
                    target,
                    target + " 不是合法 JSON，审查无法覆盖该部分。",
                    List.of()
            ));
            return null;
        }
    }

    private List<String> extractEvidenceIds(JsonNode itemNode) {
        JsonNode evidenceIdsNode = itemNode.get("evidenceIds");
        if (evidenceIdsNode == null || !evidenceIdsNode.isArray()) {
            return List.of();
        }
        List<String> evidenceIds = new ArrayList<>();
        for (JsonNode evidenceIdNode : evidenceIdsNode) {
            if (evidenceIdNode.isTextual() && TextUtil.hasText(evidenceIdNode.asText())) {
                evidenceIds.add(evidenceIdNode.asText().trim());
            }
        }
        return evidenceIds;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        return fieldNode.asText("");
    }

    private Set<String> validEvidenceIds(List<PrePublishEvidenceRef> evidenceRefs) {
        Set<String> evidenceIds = new HashSet<>();
        if (evidenceRefs == null) {
            return evidenceIds;
        }
        for (PrePublishEvidenceRef evidenceRef : evidenceRefs) {
            if (evidenceRef != null && TextUtil.hasText(evidenceRef.evidenceId())) {
                evidenceIds.add(evidenceRef.evidenceId().trim());
            }
        }
        return evidenceIds;
    }

    private PrePublishAuditReport buildReport(String status, int score, List<PrePublishAuditIssue> issues) {
        String summary = issues.isEmpty()
                ? "审查通过，未发现明显证据引用或夸大承诺问题。"
                : "审查发现 " + issues.size() + " 个需要复核的问题。";
        return new PrePublishAuditReport(
                status,
                score,
                summary,
                List.copyOf(issues),
                OffsetDateTime.now().toString()
        );
    }

    private String resolveStatus(List<PrePublishAuditIssue> issues) {
        boolean hasError = issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity()));
        if (hasError) {
            return "AUDIT_FAILED";
        }
        if (!issues.isEmpty()) {
            return "AUDIT_WARNED";
        }
        return "AUDIT_PASSED";
    }

    private int calculateScore(List<PrePublishAuditIssue> issues) {
        int score = INITIAL_SCORE;
        for (PrePublishAuditIssue issue : issues) {
            score -= "ERROR".equals(issue.severity()) ? ERROR_DEDUCT_SCORE : WARN_DEDUCT_SCORE;
        }
        return Math.max(score, 0);
    }
}
