package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.AudienceScreeningRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.StructuredCallResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 用同一份摘要、问题和时间轴一次生成三类观众试映，不再次读取视频。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class AudienceScreeningService {

    private static final String PROMPT_VERSION = "audience_screening_v1";
    private static final Set<String> PERSONAS = Set.of("CASUAL", "TARGET", "CORE_FAN");

    private final PreflightReviewMapper mapper;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public AudienceScreeningService(PreflightReviewMapper mapper,
                                    LLMService llmService,
                                    ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public Result screen(PreflightReviewRecord review, PreflightStepRecord step) {
        List<PreflightIssueRecord> issues = mapper.listIssues(review.reviewId());
        List<TimelineEvidenceRecord> evidence = mapper.listEvidence(review.reviewId());
        String callId = UUID.randomUUID().toString();
        String fingerprint = sha256(review.inputFingerprint() + "|" + issues.stream()
                .map(issue -> issue.issueId() + ":" + issue.severity() + ":" + issue.confidence())
                .collect(java.util.stream.Collectors.joining("|")));
        if (mapper.insertTextScreeningCall(callId, review.taskId(), review.versionId(), review.reviewId(),
                step.stepId(), fingerprint) != 1) {
            throw new AudienceScreeningException("观众试映调用记录创建失败");
        }
        try {
            StructuredCallResult<AudienceOutput> call = llmService.chatStructuredWithUsage(
                    systemPrompt(), userPrompt(review, issues, evidence), AudienceOutput.class);
            List<ValidatedPersona> personas = validate(call.entity(), issues, evidence);
            persist(review.reviewId(), personas, issues, call.entity());
            if (mapper.completeTextScreeningCall(callId, call.promptTokens(), call.completionTokens(),
                    personas.size()) != 1) {
                throw new AudienceScreeningException("观众试映用量保存失败");
            }
            return new Result(personas.size(), call.promptTokens(), call.completionTokens());
        } catch (RuntimeException exception) {
            mapper.failTextScreeningCall(callId, "AUDIENCE_SCREENING_FAILED", truncate(exception.getMessage()));
            throw exception instanceof AudienceScreeningException
                    ? exception : new AudienceScreeningException("三类观众试映生成失败", exception);
        }
    }

    private void persist(String reviewId,
                         List<ValidatedPersona> personas,
                         List<PreflightIssueRecord> issues,
                         AudienceOutput rawOutput) {
        mapper.deleteAudienceScreenings(reviewId);
        String normalizedRaw = json(rawOutput);
        for (ValidatedPersona persona : personas) {
            AudienceScreeningRecord record = new AudienceScreeningRecord(
                    null, UUID.randomUUID().toString(), reviewId, persona.personaType(),
                    json(Map.of(
                            "definition", personaDefinition(persona.personaType()),
                            "affectedIssueIds", persona.affectedIssueIds()
                    )),
                    persona.overallReaction(), json(persona.interestPoints()),
                    json(persona.confusionPoints()), json(persona.dropRisks()),
                    json(persona.evidenceRefs()), persona.confidence(), PROMPT_VERSION,
                    normalizedRaw, null, null
            );
            if (mapper.insertAudienceScreening(record) <= 0) {
                throw new AudienceScreeningException("观众试映结果保存失败");
            }
        }
        Map<String, LinkedHashSet<String>> affected = new HashMap<>();
        issues.forEach(issue -> affected.put(issue.issueId(), new LinkedHashSet<>()));
        personas.forEach(persona -> persona.affectedIssueIds().forEach(issueId ->
                affected.computeIfAbsent(issueId, ignored -> new LinkedHashSet<>()).add(persona.personaType())));
        affected.forEach((issueId, personaTypes) -> {
            if (mapper.updateIssueAffectedPersonas(reviewId, issueId, json(personaTypes)) != 1) {
                throw new AudienceScreeningException("问题受影响观众保存失败");
            }
        });
    }

    private List<ValidatedPersona> validate(AudienceOutput output,
                                             List<PreflightIssueRecord> issues,
                                             List<TimelineEvidenceRecord> evidence) {
        if (output == null || output.personas() == null || output.personas().size() != 3) {
            throw new AudienceScreeningException("观众试映必须返回三类角色");
        }
        Set<String> validIssueIds = issues.stream().map(PreflightIssueRecord::issueId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> validEvidenceIds = evidence.stream().map(TimelineEvidenceRecord::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<ValidatedPersona> result = new ArrayList<>();
        for (PersonaOutput item : output.personas()) {
            String personaType = text(item == null ? null : item.personaType(), "观众角色").toUpperCase();
            if (!PERSONAS.contains(personaType) || !seen.add(personaType)) {
                throw new AudienceScreeningException("观众试映角色缺失或重复");
            }
            BigDecimal confidence = item.confidence();
            if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new AudienceScreeningException("观众试映置信度无效");
            }
            List<String> affectedIssueIds = safeList(item.affectedIssueIds()).stream()
                    .filter(validIssueIds::contains).distinct().toList();
            List<String> evidenceRefs = safeList(item.evidenceRefs()).stream()
                    .filter(validEvidenceIds::contains).distinct().toList();
            if (evidenceRefs.isEmpty() && !evidence.isEmpty()) {
                evidenceRefs = List.of(evidence.get(0).evidenceId());
            }
            String reaction = text(item.overallReaction(), "整体反应");
            if (!(reaction.contains("可能") || reaction.contains("倾向") || reaction.contains("假设"))) {
                reaction = "试映假设：" + reaction;
            }
            result.add(new ValidatedPersona(
                    personaType,
                    reaction,
                    safeList(item.interestPoints()),
                    safeList(item.confusionPoints()),
                    safeList(item.dropRisks()),
                    evidenceRefs,
                    affectedIssueIds,
                    confidence
            ));
        }
        return result;
    }

    private String systemPrompt() {
        return """
                你是 B 站创作者的发布前三类观众试映助手。你没有真实观众数据，只能基于给定摘要、问题和证据做试映假设。
                必须一次返回 CASUAL、TARGET、CORE_FAN 三类角色，不得声称已经采访真实用户，不得给出精确播放量、留存率或转化率。
                结论使用“可能、倾向、试映假设”等克制表达，并给创作者简短、可理解的反馈。
                """;
    }

    private String userPrompt(PreflightReviewRecord review,
                              List<PreflightIssueRecord> issues,
                              List<TimelineEvidenceRecord> evidence) {
        String issueText = issues.stream().limit(40)
                .map(issue -> issue.issueId() + " | " + issue.severity() + " | " + issue.startMs()
                        + "-" + issue.endMs() + "ms | " + issue.title() + " | " + issue.description())
                .collect(java.util.stream.Collectors.joining("\n"));
        String evidenceText = evidence.stream().limit(160)
                .map(item -> item.evidenceId() + " | " + item.startMs() + "-" + item.endMs()
                        + "ms | " + item.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                视频体检摘要：%s
                作者关注重点：%s

                已合并问题：
                %s

                压缩时间轴证据：
                %s

                角色关注点：
                - CASUAL：前 30 秒是否看懂、是否愿意继续、哪里可能感到拖沓。
                - TARGET：核心承诺是否兑现、步骤是否有用、还缺什么证据。
                - CORE_FAN：是否符合创作者既有风格、是否重复、期待是否被满足。

                每个角色返回：personaType、overallReaction、interestPoints、confusionPoints、dropRisks、
                evidenceRefs、affectedIssueIds、confidence。引用只能使用上面出现的 evidenceId 和 issueId。
                """.formatted(
                review.executiveSummary() == null ? "暂无摘要" : review.executiveSummary(),
                review.reviewFocus() == null ? "无额外重点" : review.reviewFocus(),
                issueText.isBlank() ? "没有明确问题" : issueText,
                evidenceText.isBlank() ? "暂无时间轴文本证据" : evidenceText
        );
    }

    private String personaDefinition(String personaType) {
        return switch (personaType) {
            case "CASUAL" -> "第一次刷到该内容的路人观众";
            case "TARGET" -> "对主题有明确需求的目标观众";
            case "CORE_FAN" -> "熟悉创作者表达方式的核心粉丝";
            default -> personaType;
        };
    }

    private List<String> safeList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(8).toList();
    }

    private String text(String value, String field) {
        if (value == null || value.isBlank()) throw new AudienceScreeningException(field + "不能为空");
        return value.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AudienceScreeningException("观众试映结果序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AudienceScreeningException("观众试映输入摘要生成失败", exception);
        }
    }

    private String truncate(String message) {
        String safe = message == null || message.isBlank() ? "观众试映失败" : message;
        return safe.substring(0, Math.min(500, safe.length()));
    }

    public record AudienceOutput(List<PersonaOutput> personas) {
    }

    public record PersonaOutput(String personaType,
                                String overallReaction,
                                List<String> interestPoints,
                                List<String> confusionPoints,
                                List<String> dropRisks,
                                List<String> evidenceRefs,
                                List<String> affectedIssueIds,
                                BigDecimal confidence) {
    }

    public record Result(int personaCount, Integer inputTokens, Integer outputTokens) {
    }

    private record ValidatedPersona(String personaType,
                                    String overallReaction,
                                    List<String> interestPoints,
                                    List<String> confusionPoints,
                                    List<String> dropRisks,
                                    List<String> evidenceRefs,
                                    List<String> affectedIssueIds,
                                    BigDecimal confidence) {
    }

    public static class AudienceScreeningException extends RuntimeException {
        public AudienceScreeningException(String message) {
            super(message);
        }

        public AudienceScreeningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
