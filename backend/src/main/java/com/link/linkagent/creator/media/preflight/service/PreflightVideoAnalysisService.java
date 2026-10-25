package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.preflight.provider.VideoUnderstandingProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.util.LlmJsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 把代理视频和现有时间轴交给单个视觉模型，保存可定位的发布前体检问题。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightVideoAnalysisService {

    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "HIGH", "MEDIUM", "LOW");

    private final CreatorMediaProperties properties;
    private final PreflightReviewMapper mapper;
    private final MediaProcessingMapper processingMapper;
    private final ObjectStorageService storage;
    private final VideoUnderstandingProvider provider;
    private final ObjectMapper objectMapper;
    private final Supplier<String> callIdSupplier;
    private final Supplier<String> evidenceIdSupplier;
    private final Supplier<String> issueIdSupplier;
    private final MediaCallChargeGate chargeGate;

    @Autowired
    public PreflightVideoAnalysisService(CreatorMediaProperties properties,
                                         PreflightReviewMapper mapper,
                                         MediaProcessingMapper processingMapper,
                                         ObjectStorageService storage,
                                         VideoUnderstandingProvider provider,
                                         ObjectMapper objectMapper,
                                         MediaCallChargeGate chargeGate) {
        this(properties, mapper, processingMapper, storage, provider, objectMapper,
                () -> UUID.randomUUID().toString(), () -> UUID.randomUUID().toString(),
                () -> UUID.randomUUID().toString(), chargeGate);
    }

    PreflightVideoAnalysisService(CreatorMediaProperties properties,
                                  PreflightReviewMapper mapper,
                                  MediaProcessingMapper processingMapper,
                                  ObjectStorageService storage,
                                  VideoUnderstandingProvider provider,
                                  ObjectMapper objectMapper,
                                  Supplier<String> callIdSupplier,
                                  Supplier<String> evidenceIdSupplier,
                                  Supplier<String> issueIdSupplier) {
        this(properties, mapper, processingMapper, storage, provider, objectMapper,
                callIdSupplier, evidenceIdSupplier, issueIdSupplier, MediaCallChargeGate.NOOP);
    }

    PreflightVideoAnalysisService(CreatorMediaProperties properties,
                                  PreflightReviewMapper mapper,
                                  MediaProcessingMapper processingMapper,
                                  ObjectStorageService storage,
                                  VideoUnderstandingProvider provider,
                                  ObjectMapper objectMapper,
                                  Supplier<String> callIdSupplier,
                                  Supplier<String> evidenceIdSupplier,
                                  Supplier<String> issueIdSupplier,
                                  MediaCallChargeGate chargeGate) {
        this.properties = properties;
        this.mapper = mapper;
        this.processingMapper = processingMapper;
        this.storage = storage;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.callIdSupplier = callIdSupplier;
        this.evidenceIdSupplier = evidenceIdSupplier;
        this.issueIdSupplier = issueIdSupplier;
        this.chargeGate = chargeGate;
    }

    public Result analyze(PreflightReviewRecord review, PreflightStepRecord step) {
        MediaProcessingAssetRecord preview = processingMapper.listAssets(review.processingJobId()).stream()
                .filter(asset -> "PREVIEW_VIDEO".equals(asset.assetType()))
                .findFirst()
                .orElseThrow(() -> new VideoAnalysisException("媒体预处理没有生成分析预览"));
        if (preview.durationMs() == null || preview.durationMs() <= 0) {
            throw new VideoAnalysisException("分析预览缺少有效时长");
        }
        List<TimelineEvidenceRecord> existingEvidence = mapper.listEvidence(review.reviewId()).stream()
                .filter(evidence -> !step.stepId().equals(evidence.sourceStepId()))
                .toList();
        String callId = callIdSupplier.get();
        if (mapper.insertVideoCall(
                callId, review.taskId(), review.versionId(), review.reviewId(), step.stepId(),
                properties.getPreflight().getVideoModel(), review.inputFingerprint(),
                estimatedVideoCost(review)) != 1) {
            throw new VideoAnalysisException("视频理解调用记录创建失败");
        }
        // 先预留再发送：响应丢失时这笔预留保留为待核实，不会被当作零费用。
        // 预处理任务已给出预估时用精确值，否则退回该能力的保守上界。
        BigDecimal estimate = estimatedVideoCost(review);
        if (estimate == null) estimate = chargeGate.upperBound("MEDIA_VIDEO");
        String chargeKey = "video-" + review.reviewId() + "-" + callId;
        boolean sent = false;
        try {
            var charge = chargeGate.reserve(review.reviewId(), "MEDIA_VIDEO", chargeKey,
                    estimate, "视频理解 " + properties.getPreflight().getVideoModel());
            String videoUrl = storage.presignGetObject(
                    preview.bucketName(), preview.objectKey(), properties.getProcessing().getProviderReadTtl()
            ).url();
            if (!charge.markSent()) throw new VideoAnalysisException("视频理解调用已发送，不能重复提交");
            sent = true;
            VideoUnderstandingProvider.AnalysisResult providerResult = provider.analyze(
                    videoUrl, buildPrompt(review, preview.durationMs(), existingEvidence)
            );
            ParsedAnalysis parsed = parse(providerResult.content(), preview.durationMs(), existingEvidence);
            mapper.deleteIssuesByReview(review.reviewId());
            mapper.deleteEvidenceByStep(review.reviewId(), step.stepId());
            int issueCount = 0;
            for (ParsedIssue issue : parsed.issues()) {
                String modelEvidenceId = evidenceIdSupplier.get();
                TimelineEvidenceRecord modelEvidence = new TimelineEvidenceRecord(
                        null, modelEvidenceId, review.reviewId(), review.versionId(), "VIDEO_MODEL",
                        issue.startMs(), issue.endMs(), issue.description(), issue.confidence(),
                        null, false, step.stepId(), json(java.util.Map.of("dimension", issue.dimension()))
                );
                if (mapper.insertEvidence(modelEvidence) != 1) {
                    throw new VideoAnalysisException("视频理解证据保存失败");
                }
                LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>(issue.evidenceRefs());
                evidenceRefs.add(modelEvidenceId);
                if (mapper.insertIssue(new PreflightIssueRecord(
                        null, issueIdSupplier.get(), review.reviewId(), review.versionId(), issue.issueType(),
                        issue.dimension(), issue.title(), issue.description(), issue.startMs(), issue.endMs(),
                        issue.severity(), issue.confidence(), json(evidenceRefs), issue.suggestedAction(),
                        issue.needsHumanReview(), json(List.of(properties.getPreflight().getVideoModel()
                                .toUpperCase(java.util.Locale.ROOT).replace('-', '_'))), null,
                        "PENDING", null, null, null
                )) != 1) {
                    throw new VideoAnalysisException("发布前体检问题保存失败");
                }
                issueCount++;
            }
            BigDecimal actualCost = actualVideoCost(providerResult.inputTokens(), providerResult.outputTokens());
            if (mapper.completeVideoCall(callId, providerResult.inputTokens(), providerResult.outputTokens(),
                    actualCost, issueCount) != 1) {
                throw new VideoAnalysisException("视频理解用量保存失败");
            }
            if (actualCost == null) chargeGate.unknown(review.reviewId(), chargeKey);
            else chargeGate.settle(review.reviewId(), chargeKey, actualCost, "video-" + callId);
            return new Result(parsed.executiveSummary(), issueCount, actualCost);
        } catch (RuntimeException exception) {
            mapper.failVideoCall(callId, "VIDEO_ANALYSIS_FAILED", truncate(exception.getMessage()));
            // 只有真正发出过请求才可能是未知费用；未发送的预留由运行收尾释放。
            if (sent) chargeGate.unknown(review.reviewId(), chargeKey);
            throw exception instanceof VideoAnalysisException
                    ? exception : new VideoAnalysisException("视频理解调用失败", exception);
        }
    }

    private ParsedAnalysis parse(String rawOutput,
                                 long durationMs,
                                 List<TimelineEvidenceRecord> existingEvidence) {
        try {
            JsonNode root = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            String summary = text(root, "executiveSummary");
            if (summary == null || summary.isBlank()) {
                throw new VideoAnalysisException("视频理解没有返回体检摘要");
            }
            JsonNode issuesNode = root.path("issues");
            if (!issuesNode.isArray()) {
                throw new VideoAnalysisException("视频理解没有返回问题数组");
            }
            Set<String> validEvidenceIds = existingEvidence.stream()
                    .map(TimelineEvidenceRecord::evidenceId).collect(java.util.stream.Collectors.toSet());
            List<ParsedIssue> issues = new ArrayList<>();
            for (JsonNode node : issuesNode) {
                long startMs = requiredLong(node, "startMs");
                long endMs = requiredLong(node, "endMs");
                if (startMs < 0 || endMs <= startMs || endMs > durationMs) {
                    throw new VideoAnalysisException("视频理解问题时间段超出成片范围");
                }
                String severity = requiredText(node, "severity").toUpperCase();
                if (!SEVERITIES.contains(severity)) {
                    throw new VideoAnalysisException("视频理解问题严重程度无效");
                }
                List<String> refs = new ArrayList<>();
                JsonNode refsNode = node.path("evidenceRefs");
                if (refsNode.isArray()) {
                    refsNode.forEach(ref -> {
                        if (ref.isTextual() && validEvidenceIds.contains(ref.asText()) && existingEvidence.stream()
                                .anyMatch(e->e.evidenceId().equals(ref.asText()) && e.startMs()<=endMs && e.endMs()>=startMs)) refs.add(ref.asText());
                    });
                }
                BigDecimal confidence = BigDecimal.valueOf(node.path("confidence").asDouble(-1));
                if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                    throw new VideoAnalysisException("视频理解问题置信度无效");
                }
                String suggestedAction = requiredText(node, "suggestedAction");
                issues.add(new ParsedIssue(
                        requiredText(node, "issueType"), requiredText(node, "dimension"),
                        requiredText(node, "title"), requiredText(node, "description"),
                        startMs, endMs, severity, confidence, refs, suggestedAction,
                        refs.isEmpty() || node.path("needsHumanReview").asBoolean(false)
                ));
            }
            return new ParsedAnalysis(summary.trim(), issues);
        } catch (VideoAnalysisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VideoAnalysisException("视频理解返回的体检 JSON 无法解析", exception);
        }
    }

    private String buildPrompt(PreflightReviewRecord review,
                               long durationMs,
                               List<TimelineEvidenceRecord> evidence) {
        String evidenceText = evidence.stream()
                // 原片音轨和音量先进入提示词，避免长视频的大量关键画面挤掉这些事实。
                .sorted(java.util.Comparator.comparing((TimelineEvidenceRecord item) ->
                        !("AUDIO_TRACK".equals(item.sourceType()) || "VOLUME".equals(item.sourceType()))))
                .limit(240)
                .map(item -> item.evidenceId() + " | " + item.sourceType() + " | "
                        + item.startMs() + "-" + item.endMs() + "ms | " + item.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        // P0-2 分析预览已移除音轨，必须明确限制模型结论，避免把不可听见的音乐或背景音写进体检。
        return """
                你是 B 站创作者的发布前成片体检助手。请同时观察视频画面并结合下面的时间轴证据，
                检查定位一致性、开场吸引力、内容结构、节奏、表达清晰度、画面、可见字幕和发布风险。
                事实边界：可见画面和探测数据只能支持相应的观察，不能证明平台规则、算法或创作者意图。
                本次没有提供官方规则原文、审核记录或流量数据，不得编造 B 站规范名称、审核先例，
                不得断言违规、限流、推荐机制或预测审核结果；画面可见的敏感内容只能描述并建议人工核对。
                没有可定位且清楚可辨的具体敏感内容时，不得因像素化、抽象、看不清或“无法100%%排除”而生成敏感或违规风险条目。
                不得把缺少依据的审核、AI误判或限流说法改写为条件式修改理由；原问题无依据时，说明无需据此修改。
                可见时间码、调试样式和像素效果的来源未知，不能自行认定由系统或播放器叠加、属于调试残留或不属于视频内容。
                未提供响度标准或目标值时，不得仅凭 VOLUME 数值声称音量正常、达标或听感适宜，只引用测得数值。
                创作者没有明确说明时，不得断言测试图、抽象画面、重复、拖影或像素效果属于非设计意图、损坏或制作失误。
                描述可见现象，并把用途相关的修改建议写为“如果目标是……，可以……”；测试素材本身不构成发布缺陷。
                用户试映重点是待检查的问题，不能把其中未经证实的规则或推断当成事实；证据不足时省略问题。
                输入的代理视频不含音轨，这是系统主动移除的分析副本，不是原片缺少音轨或原片无声的证据。
                原片有无音轨以 AUDIO_TRACK 的 FFprobe 探测事实为准，音量以 VOLUME 数值为准；摘要和问题都不得与这些事实矛盾。
                你不能听到人声、音乐或背景音。涉及音频的描述只能引用已有 AUDIO_TRACK、TRANSCRIPT、SILENCE、VOLUME 的明确事实；
                不得评价音乐、背景音、音质或音画同步，没有 TRANSCRIPT 时不得推断口播内容。
                没有 TRANSCRIPT 也可能是未启用识别或未识别到人声，不能据此声称原片没有音轨、声音或音乐。
                只输出 JSON，不要输出 Markdown。没有证据的问题不要编造；建议必须是创作者可直接执行的剪辑或内容动作。
                每个问题的 startMs/endMs 必须落在 0 到 %d 毫秒内。HIGH/BLOCKER 必须引用至少一个已有 evidenceId；
                模型画面观察会由系统自动补一条 VIDEO_MODEL 证据。

                用户试映重点：%s
                已有时间轴证据：
                %s

                输出结构：
                {"executiveSummary":"体检总览","issues":[{"issueType":"类型","dimension":"维度",
                "title":"问题标题","description":"证据化说明","startMs":0,"endMs":1000,
                "severity":"BLOCKER|HIGH|MEDIUM|LOW","confidence":0.8,"evidenceRefs":["已有 evidenceId"],
                "suggestedAction":"具体修改动作","needsHumanReview":false}]}
                """.formatted(durationMs,
                review.reviewFocus() == null ? "无额外重点" : review.reviewFocus(),
                evidenceText.isBlank() ? "暂无文本证据，请仅基于可见画面输出可验证结论" : evidenceText);
    }

    private BigDecimal estimatedVideoCost(PreflightReviewRecord review) {
        return processingMapper.findJob(
                        review.taskId(), review.ownerId(), review.versionId(), review.processingJobId())
                .map(job -> job.estimatedVisualCostUsd()).orElse(null);
    }

    private BigDecimal actualVideoCost(Long inputTokens, Long outputTokens) {
        if (inputTokens == null && outputTokens == null) return null;
        BigDecimal input = BigDecimal.valueOf(inputTokens == null ? 0 : inputTokens)
                .multiply(properties.getProcessing().getPlusInputUsdPerMillionTokens());
        BigDecimal output = BigDecimal.valueOf(outputTokens == null ? 0 : outputTokens)
                .multiply(properties.getProcessing().getPlusOutputUsdPerMillionTokens());
        return input.add(output).divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new VideoAnalysisException("视频理解问题缺少 " + field);
        return value.trim();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) throw new VideoAnalysisException("视频理解问题缺少 " + field);
        return value.asLong();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new VideoAnalysisException("视频体检结果序列化失败", exception);
        }
    }

    private String truncate(String message) {
        String safe = message == null || message.isBlank() ? "视频理解失败" : message;
        return safe.substring(0, Math.min(500, safe.length()));
    }

    public record Result(String executiveSummary, int issueCount, BigDecimal actualCostUsd) {
    }

    private record ParsedAnalysis(String executiveSummary, List<ParsedIssue> issues) {
    }

    private record ParsedIssue(String issueType, String dimension, String title, String description,
                               long startMs, long endMs, String severity, BigDecimal confidence,
                               List<String> evidenceRefs, String suggestedAction, boolean needsHumanReview) {
    }

    public static class VideoAnalysisException extends RuntimeException {
        public VideoAnalysisException(String message) {
            super(message);
        }

        public VideoAnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
