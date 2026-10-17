package com.link.linkagent.assistant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 平台助手使用独立契约，避免把用户原话和服务端业务快照混在一起保存。 */
public final class AssistantModels {
    private AssistantModels() { }

    public record ChatContext(
            @NotBlank @Size(max = 80) String page,
            @Size(max = 64) String taskId,
            @Size(max = 40) String step) {
        public ChatContext(String page,String taskId) { this(page,taskId,null); }
        public ChatContext {
            page=page==null ? null : page.trim();
            taskId=taskId==null || taskId.isBlank() ? null : taskId.trim();
        }
    }

    public enum ActionType { ANALYZE_FEEDBACK, GENERATE_REVIEW }

    public record PrepareAction(
            @NotBlank @Size(max = 64) String taskId,
            @NotNull ActionType actionType,
            @Size(max = 2000) String guidance) {
        public PrepareAction { taskId=taskId==null ? null : taskId.trim(); }
    }

    public record ConfirmAction(
            @NotBlank @Size(max = 64) String taskId,
            @NotNull @AssertTrue(message = "请先确认具体操作") Boolean confirmed) { }

    public record ActionProposal(String proposalId, String taskId, String taskName, ActionType actionType,
                                 String guidance, String description, String sourceFingerprint,
                                 String status, String resultMessage,
                                 LocalDateTime createTime, LocalDateTime expiresAt) { }

    public record TaskContext(String page, String taskId, String taskName, String taskStatus,
                              String blockingReason, Map<String, Object> facts,
                              List<String> availableActions) { }

    public record AgentTrace(String runId, String taskId, String scene, String executionMode,
                             String status, String traceJson, String errorMessage,
                             LocalDateTime createTime) { }
}
