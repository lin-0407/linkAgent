package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantModels.*;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 模型只能准备提议；确认接口使用数据库抢占，一次提议至多发起一次业务调用。 */
@Service
public class AssistantActionService {
    private final AssistantMapper mapper;
    private final PlatformAssistantContextService context;
    private final CreatorTaskMapper tasks;
    private final CreatorFeedbackService feedbackService;
    private final CreatorReportService reportService;

    public AssistantActionService(AssistantMapper mapper, PlatformAssistantContextService context,
            CreatorTaskMapper tasks,
            CreatorFeedbackService feedbackService, CreatorReportService reportService) {
        this.mapper=mapper; this.context=context; this.tasks=tasks;
        this.feedbackService=feedbackService; this.reportService=reportService;
    }

    public ActionProposal prepare(PrepareAction request) {
        var task=tasks.findTaskByTaskId(request.taskId()).orElseThrow(() ->
                new ResponseStatusException(NOT_FOUND,"创作任务不存在"));
        ensureAvailable(request.taskId(),request.actionType());
        String description=request.actionType()==ActionType.ANALYZE_FEEDBACK
                ? "读取已保存的评论弹幕并生成反馈报告。会调用模型并产生费用；重新分析会使旧总体复盘及其偏好失效。"
                : "读取发布后资料和反馈，通过多 Agent 分工生成总体复盘。会调用模型并产生费用；新报告会保存到复盘历史并更新偏好。";
        var now=LocalDateTime.now();
        var proposal=new ActionProposal(UUID.randomUUID().toString(),request.taskId(),task.getTaskName(),
                request.actionType(),request.guidance()==null ? "" : request.guidance().trim(),description,
                fingerprint(request.taskId(),request.actionType()),"PENDING",null,now,now.plusMinutes(15));
        mapper.insertAction(proposal);
        return proposal;
    }

    public ActionProposal confirm(String proposalId, ConfirmAction request) {
        if (!Boolean.TRUE.equals(request.confirmed())) throw new ResponseStatusException(BAD_REQUEST,"请先确认具体操作");
        var proposal=mapper.findAction(proposalId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND,"操作提议不存在"));
        if (!proposal.taskId().equals(request.taskId())) throw new ResponseStatusException(CONFLICT,"当前任务与操作提议不一致");
        // 已经执行的提议只返回状态，网络重试和双击都不会再次调用模型。
        if (!"PENDING".equals(proposal.status())) return proposal;
        if (!proposal.expiresAt().isAfter(LocalDateTime.now())) throw new ResponseStatusException(CONFLICT,"操作提议已过期，请重新准备");
        ensureAvailable(proposal.taskId(),proposal.actionType());
        if (!proposal.sourceFingerprint().equals(fingerprint(proposal.taskId(),proposal.actionType()))) {
            throw new ResponseStatusException(CONFLICT,"任务数据已经变化，请重新查看并准备操作");
        }
        // 使用提议创建时同一时钟口径，避免 MySQL 容器时区和 Java 不同导致过期判断失真。
        if (mapper.claimAction(proposalId,LocalDateTime.now())!=1) {
            var current=mapper.findAction(proposalId).orElseThrow();
            if ("PENDING".equals(current.status())) throw new ResponseStatusException(CONFLICT,"操作提议已过期，请重新准备");
            return current;
        }
        try {
            if (proposal.actionType()==ActionType.ANALYZE_FEEDBACK) {
                feedbackService.analyze(proposal.taskId(),new CreatorFeedbackAnalyzeRequest(proposal.guidance(),null,null));
            } else {
                reportService.analyze(proposal.taskId(),new CreatorReportAnalyzeRequest(proposal.guidance(),null,null));
            }
            mapper.finishAction(proposalId,"SUCCEEDED","结果已保存，可在对应业务页面查看。");
        } catch (RuntimeException error) {
            // 不自动重试：业务可能已经保存，重新调用会重复产生模型费用。
            mapper.finishAction(proposalId,"FAILED","执行未完成，请查看业务结果后重新准备操作。");
            throw error;
        }
        return mapper.findAction(proposalId).orElseThrow();
    }

    public List<ActionProposal> list(String taskId) {
        context.inspect("/creator",taskId);
        return mapper.listActions(taskId);
    }

    private void ensureAvailable(String taskId,ActionType type) {
        var snapshot=context.inspect("/creator",taskId);
        if (!snapshot.availableActions().contains(type.name())) {
            throw new ResponseStatusException(CONFLICT,snapshot.blockingReason()!=null ? snapshot.blockingReason()
                    : type==ActionType.ANALYZE_FEEDBACK ? "请先保存评论或弹幕样例" : "请先完成有效的反馈分析");
        }
    }

    private String fingerprint(String taskId,ActionType type) {
        // 比较完整来源，避免样例正文已改但条数相同的情况绕过确认。
        return digest(context.actionSource(taskId)+"|"+type.name());
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
