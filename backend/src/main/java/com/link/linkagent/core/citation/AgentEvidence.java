package com.link.linkagent.core.citation;

import com.link.linkagent.util.TextUtil;

/**
 * Agent 可引用的最小证据单元。
 * <p>
 * 每条最终回答里的事实性句子都应该能追到这里，而不是追到一整段不可定位的 Worker 原文。
 */
public record AgentEvidence(
        String evidenceId,
        EvidenceSourceType sourceType,
        String sourceRef,
        String content,
        String quote,
        Double confidence
) {

    private static final int CONTENT_PREVIEW_LENGTH = 700;
    private static final int QUOTE_PREVIEW_LENGTH = 240;

    public AgentEvidence {
        evidenceId = TextUtil.trimToDefault(evidenceId, "UNKNOWN-EVIDENCE");
        sourceType = sourceType == null ? EvidenceSourceType.SYSTEM_LIMITATION : sourceType;
        sourceRef = TextUtil.trimToDefault(sourceRef, "未说明来源位置");
        content = TextUtil.preview(content, CONTENT_PREVIEW_LENGTH, "无可用证据内容");
        quote = TextUtil.preview(quote, QUOTE_PREVIEW_LENGTH, content);
        confidence = confidence == null ? 0.5D : Math.max(0D, Math.min(1D, confidence));
    }

    public static AgentEvidence fromPlanStep(int stepId, String action, String observation) {
        String evidenceId = "P" + stepId + "-E1";
        return new AgentEvidence(
                evidenceId,
                EvidenceSourceType.TOOL_OBSERVATION,
                "plan.step." + stepId + ":" + TextUtil.trimToDefault(action, "unknown_tool"),
                observation,
                observation,
                0.85D
        );
    }

    public static AgentEvidence fromWorkerPlanStep(int callId, int stepId, String action, String observation) {
        String evidenceId = "W" + callId + "-P" + stepId + "-E1";
        return new AgentEvidence(
                evidenceId,
                EvidenceSourceType.TOOL_OBSERVATION,
                "worker." + callId + ".plan.step." + stepId + ":" + TextUtil.trimToDefault(action, "unknown_tool"),
                observation,
                observation,
                0.85D
        );
    }

    public static AgentEvidence fromWorkerSummary(int callId, String workerName, String summary) {
        String evidenceId = "W" + callId + "-S1";
        return new AgentEvidence(
                evidenceId,
                EvidenceSourceType.WORKER_REASONING,
                "worker." + callId + ":" + TextUtil.trimToDefault(workerName, "unknown_worker"),
                summary,
                summary,
                0.6D
        );
    }
}
