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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 只切出少量重点片段交给 Plus 复核，避免更强模型重复读取整片。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightSegmentReviewService {

    private static final Logger log = LoggerFactory.getLogger(PreflightSegmentReviewService.class);
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "HIGH", "MEDIUM", "LOW");

    private final CreatorMediaProperties properties;
    private final PreflightReviewMapper mapper;
    private final MediaProcessingMapper processingMapper;
    private final ObjectStorageService storage;
    private final VideoUnderstandingProvider provider;
    private final ObjectMapper objectMapper;
    private final Supplier<String> callIdSupplier;
    private final Supplier<String> evidenceIdSupplier;
    private final MediaCallChargeGate chargeGate;

    @Autowired
    public PreflightSegmentReviewService(CreatorMediaProperties properties,
                                         PreflightReviewMapper mapper,
                                         MediaProcessingMapper processingMapper,
                                         ObjectStorageService storage,
                                         VideoUnderstandingProvider provider,
                                         ObjectMapper objectMapper,
                                         MediaCallChargeGate chargeGate) {
        this(properties, mapper, processingMapper, storage, provider, objectMapper,
                () -> UUID.randomUUID().toString(), () -> UUID.randomUUID().toString(), chargeGate);
    }

    PreflightSegmentReviewService(CreatorMediaProperties properties,
                                  PreflightReviewMapper mapper,
                                  MediaProcessingMapper processingMapper,
                                  ObjectStorageService storage,
                                  VideoUnderstandingProvider provider,
                                  ObjectMapper objectMapper,
                                  Supplier<String> callIdSupplier,
                                  Supplier<String> evidenceIdSupplier) {
        this(properties, mapper, processingMapper, storage, provider, objectMapper,
                callIdSupplier, evidenceIdSupplier, MediaCallChargeGate.NOOP);
    }

    PreflightSegmentReviewService(CreatorMediaProperties properties,
                                  PreflightReviewMapper mapper,
                                  MediaProcessingMapper processingMapper,
                                  ObjectStorageService storage,
                                  VideoUnderstandingProvider provider,
                                  ObjectMapper objectMapper,
                                  Supplier<String> callIdSupplier,
                                  Supplier<String> evidenceIdSupplier,
                                  MediaCallChargeGate chargeGate) {
        this.properties = properties;
        this.mapper = mapper;
        this.processingMapper = processingMapper;
        this.storage = storage;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.callIdSupplier = callIdSupplier;
        this.evidenceIdSupplier = evidenceIdSupplier;
        this.chargeGate = chargeGate;
    }

    public Result review(PreflightReviewRecord review, PreflightStepRecord step) {
        boolean plusEnabled = processingMapper.findJob(
                        review.taskId(), review.ownerId(), review.versionId(), review.processingJobId())
                .map(job -> "FLASH_PLUS_REVIEW".equals(job.modelPlan()))
                .orElse(false);
        if (!plusEnabled) return new Result(0, 0, 0, BigDecimal.ZERO);
        List<PreflightIssueRecord> candidates = mapper.listIssues(review.reviewId()).stream()
                .filter(issue -> "BLOCKER".equals(issue.severity()) || "HIGH".equals(issue.severity())
                        || issue.confidence().compareTo(new BigDecimal("0.65")) < 0)
                .limit(properties.getPreflight().getSegmentReviewMaxCount())
                .toList();
        if (candidates.isEmpty()) return new Result(0, 0, 0, BigDecimal.ZERO);

        MediaProcessingAssetRecord preview = processingMapper.listAssets(review.processingJobId()).stream()
                .filter(asset -> "PREVIEW_VIDEO".equals(asset.assetType()))
                .findFirst()
                .orElse(null);
        if (preview == null || preview.durationMs() == null || preview.durationMs() <= 0) {
            return new Result(candidates.size(), 0, candidates.size(), BigDecimal.ZERO);
        }

        Path workDir = resolveWorkDir(review.reviewId());
        Path previewFile = workDir.resolve("preview.mp4");
        int reviewedCount = 0;
        int failedCount = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        try {
            deleteTree(workDir);
            Files.createDirectories(workDir);
            storage.downloadObject(preview.bucketName(), preview.objectKey(), previewFile);
            for (int index = 0; index < candidates.size(); index++) {
                PreflightIssueRecord issue = candidates.get(index);
                SegmentRange range = segmentRange(issue, preview.durationMs());
                Path clip = workDir.resolve("segment-" + (index + 1) + ".mp4");
                String objectKey = temporaryObjectKey(review, issue);
                String callId = callIdSupplier.get();
                boolean uploaded = false;
                boolean sent = false;
                String chargeKey = "segment-" + review.reviewId() + "-" + callId;
                try {
                    cutClip(previewFile, clip, range);
                    storage.putObject(preview.bucketName(), objectKey, clip, "video/mp4");
                    uploaded = true;
                    String requestFingerprint = sha256(review.inputFingerprint() + "|" + issue.issueId()
                            + "|" + range.startMs() + "|" + range.endMs());
                    if (mapper.insertVideoCall(callId, review.taskId(), review.versionId(), review.reviewId(),
                            step.stepId(), properties.getPreflight().getSegmentReviewModel(),
                            requestFingerprint, null) != 1) {
                        throw new SegmentReviewException("重点片段复核调用记录创建失败");
                    }
                    // 每个片段都是独立物理调用，先按保守上界预留再发送。
                    var charge = chargeGate.reserve(review.reviewId(), "MEDIA_SEGMENT", chargeKey,
                            chargeGate.upperBound("MEDIA_SEGMENT"),
                            "重点片段复核 " + properties.getPreflight().getSegmentReviewModel());
                    String videoUrl = storage.presignGetObject(
                            preview.bucketName(), objectKey, properties.getProcessing().getProviderReadTtl()).url();
                    if (!charge.markSent()) throw new SegmentReviewException("重点片段复核调用已发送，不能重复提交");
                    sent = true;
                    VideoUnderstandingProvider.AnalysisResult providerResult = provider.analyze(
                            videoUrl,
                            buildPrompt(issue, range, mapper.listEvidence(review.reviewId())),
                            properties.getPreflight().getSegmentReviewModel(),
                            properties.getPreflight().getSegmentReviewFps()
                    );
                    ParsedReview parsed = parse(providerResult.content());
                    persistReview(review, step, issue, range, parsed);
                    BigDecimal cost = actualCost(providerResult.inputTokens(), providerResult.outputTokens());
                    mapper.completeVideoCall(callId, providerResult.inputTokens(), providerResult.outputTokens(), cost, 1);
                    if (cost == null) chargeGate.unknown(review.reviewId(), chargeKey);
                    else chargeGate.settle(review.reviewId(), chargeKey, cost, "segment-" + callId);
                    totalCost = totalCost.add(cost == null ? BigDecimal.ZERO : cost);
                    reviewedCount++;
                } catch (RuntimeException | IOException exception) {
                    mapper.failVideoCall(callId, "SEGMENT_REVIEW_FAILED", truncate(exception.getMessage()));
                    // 只有发出过请求才可能是未知费用；未发送的预留随运行收尾释放。
                    if (sent) chargeGate.unknown(review.reviewId(), chargeKey);
                    failedCount++;
                    log.warn("重点片段复核降级 reviewId={} issueId={}", review.reviewId(), issue.issueId(), exception);
                } finally {
                    if (uploaded) {
                        try {
                            storage.deleteObject(preview.bucketName(), objectKey);
                        } catch (RuntimeException exception) {
                            log.warn("重点复核临时片段删除失败 objectKey={}", objectKey, exception);
                        }
                    }
                    try {
                        Files.deleteIfExists(clip);
                    } catch (IOException ignored) {
                        // 整个工作目录会在本步骤结束时再次清理。
                    }
                }
            }
        } catch (RuntimeException | IOException exception) {
            failedCount = candidates.size();
            reviewedCount = 0;
            log.warn("重点片段复核整体降级 reviewId={}", review.reviewId(), exception);
        } finally {
            deleteTree(workDir);
        }
        return new Result(candidates.size(), reviewedCount, failedCount,
                totalCost.setScale(8, RoundingMode.HALF_UP));
    }

    private void persistReview(PreflightReviewRecord review,
                               PreflightStepRecord step,
                               PreflightIssueRecord issue,
                               SegmentRange range,
                               ParsedReview parsed) {
        String evidenceId = evidenceIdSupplier.get();
        TimelineEvidenceRecord evidence = new TimelineEvidenceRecord(
                null, evidenceId, review.reviewId(), review.versionId(), "VIDEO_MODEL",
                range.startMs(), range.endMs(), parsed.description(), parsed.confidence(),
                null, false, step.stepId(), json(java.util.Map.of(
                        "model", properties.getPreflight().getSegmentReviewModel(),
                        "issueId", issue.issueId(),
                        "confirmed", parsed.confirmed(),
                        "originalTitle", issue.title()
                ))
        );
        if (mapper.insertEvidence(evidence) != 1) {
            throw new SegmentReviewException("重点片段复核证据保存失败");
        }
        LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>(stringList(issue.evidenceRefs()));
        evidenceRefs.add(evidenceId);
        LinkedHashSet<String> sourceTypes = new LinkedHashSet<>(stringList(issue.sourceTypes()));
        sourceTypes.add("QWEN3_VL_PLUS");
        if (mapper.updateIssueAfterSegmentReview(
                review.reviewId(), issue.issueId(),
                parsed.confirmed() ? issue.title() : "原问题未被复核确认", parsed.description(), parsed.severity(),
                parsed.confidence(), parsed.suggestedAction(), json(evidenceRefs), json(sourceTypes)
        ) != 1) {
            throw new SegmentReviewException("重点片段复核问题保存失败");
        }
    }

    private ParsedReview parse(String rawOutput) {
        try {
            JsonNode root = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            String severity = requiredText(root, "severity").toUpperCase();
            if (!SEVERITIES.contains(severity)) throw new SegmentReviewException("重点复核严重程度无效");
            BigDecimal confidence = BigDecimal.valueOf(root.path("confidence").asDouble(-1));
            if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new SegmentReviewException("重点复核置信度无效");
            }
            boolean confirmed = root.path("confirmed").asBoolean(true);
            return new ParsedReview(
                    confirmed,
                    confirmed ? severity : "LOW",
                    confidence,
                    requiredText(root, "description"),
                    // 否认问题后仍返回“避免误判”等建议会继续污染报告，未确认时不让这种动作进入修改清单。
                    confirmed ? requiredText(root, "suggestedAction") : "原问题未被复核确认，无需据此修改。"
            );
        } catch (SegmentReviewException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SegmentReviewException("重点片段复核结果无法解析", exception);
        }
    }

    private String buildPrompt(PreflightIssueRecord issue,
                               SegmentRange range,
                               List<TimelineEvidenceRecord> evidence) {
        String nearbyEvidence = evidence.stream()
                .filter(item -> "AUDIO_TRACK".equals(item.sourceType()) || "VOLUME".equals(item.sourceType())
                        || item.endMs() >= range.startMs() && item.startMs() <= range.endMs())
                // 原片音轨和音量先进入提示词，避免长视频的大量关键画面挤掉这些事实。
                .sorted(java.util.Comparator.comparing((TimelineEvidenceRecord item) ->
                        !("AUDIO_TRACK".equals(item.sourceType()) || "VOLUME".equals(item.sourceType()))))
                .limit(30)
                .map(item -> item.evidenceId() + " | " + item.sourceType() + " | " + item.startMs() + "-" + item.endMs()
                        + "ms | " + item.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                你正在复核一个已经由全片粗审提出的重点问题。只观察当前短片，不要推断短片之外的内容。
                请校正严重程度、置信度、证据化说明和可执行修改动作。若原问题不成立，confirmed=false，
                severity 使用 LOW，并直白说明为什么不成立。只输出 JSON，不要输出 Markdown。
                原问题、原说明、原建议和 VIDEO_MODEL 都是待核实的模型判断，不是独立事实或官方依据。
                本次没有提供官方规则原文、审核记录或流量数据，不得沿用或编造 B 站规范名称、审核先例，
                不得断言违规、限流、推荐机制或预测审核结果；可见敏感内容只描述并建议人工核对。
                没有可定位且清楚可辨的具体敏感内容时，不得因像素化、抽象、看不清或“无法100%%排除”而生成敏感或违规风险条目。
                不得把缺少依据的审核、AI误判或限流说法改写为条件式修改理由；原问题无依据时，说明无需据此修改。
                可见时间码、调试样式和像素效果的来源未知，不能自行认定由系统或播放器叠加、属于调试残留或不属于视频内容。
                未提供响度标准或目标值时，不得仅凭 VOLUME 数值声称音量正常、达标或听感适宜，只引用测得数值。
                创作者没有明确说明时，不得把测试图、抽象画面、重复、拖影或像素效果断言为非设计意图、损坏或制作失误。
                若原问题仅依赖上述无依据判断，应返回 confirmed=false；不得另造平台理由来确认原问题。
                修改建议必须针对已证实的可见现象；用途不明确时用“如果目标是……，可以……”表达。
                当前短片是主动移除音轨的分析副本，不能凭听不到声音判断原片无音轨或无声。
                原片音轨以 AUDIO_TRACK 的 FFprobe 事实为准，音量以 VOLUME 为准；没有转写文本不代表原片无声。
                不得评价音乐、背景音、音质或音画同步；若原问题违背原片音轨事实，应判为不成立。

                原问题：%s
                原说明：%s
                原建议：%s
                短片对应整片时间：%d-%dms
                同时间段证据：
                %s

                输出结构：
                {"confirmed":true,"severity":"BLOCKER|HIGH|MEDIUM|LOW","confidence":0.8,
                "description":"复核后的证据化说明","suggestedAction":"创作者可直接执行的动作"}
                """.formatted(issue.title(), issue.description(), issue.suggestedAction(),
                range.startMs(), range.endMs(), nearbyEvidence.isBlank() ? "暂无额外文本证据" : nearbyEvidence);
    }

    private SegmentRange segmentRange(PreflightIssueRecord issue, long durationMs) {
        long start = Math.max(0, issue.startMs() - 5_000L);
        long end = Math.min(durationMs, issue.endMs() + 5_000L);
        if (end - start < 10_000L) {
            end = Math.min(durationMs, start + 10_000L);
            start = Math.max(0, end - 10_000L);
        }
        if (end - start > 45_000L) end = start + 45_000L;
        return new SegmentRange(start, end);
    }

    private void cutClip(Path source, Path target, SegmentRange range) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(properties.getProcessing().getFfmpegPath());
        command.addAll(List.of(
                "-y", "-hide_banner", "-loglevel", "error",
                "-ss", seconds(range.startMs()), "-i", source.toString(),
                "-t", seconds(range.endMs() - range.startMs()),
                "-map", "0:v:0", "-an", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "26", "-pix_fmt", "yuv420p", "-movflags", "+faststart", target.toString()
        ));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output), "preflight-segment-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(properties.getProcessing().getFfmpegTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SegmentReviewException("重点片段裁切超时");
            }
            reader.join(2_000L);
            if (process.exitValue() != 0 || !Files.exists(target)) {
                throw new SegmentReviewException("重点片段裁切失败");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SegmentReviewException("重点片段裁切被中断", exception);
        }
    }

    private Path resolveWorkDir(String reviewId) {
        Path root = Path.of(properties.getProcessing().getWorkRoot()).toAbsolutePath().normalize();
        Path candidate = root.resolve("preflight-" + reviewId).normalize();
        if (!candidate.startsWith(root)) throw new SegmentReviewException("重点复核工作目录无效");
        return candidate;
    }

    private String temporaryObjectKey(PreflightReviewRecord review, PreflightIssueRecord issue) {
        return "users/" + review.ownerId() + "/tasks/" + review.taskId() + "/versions/"
                + review.versionId() + "/preflight/" + review.reviewId() + "/segments/"
                + issue.issueId() + ".mp4";
    }

    private BigDecimal actualCost(Long inputTokens, Long outputTokens) {
        // 复核也要保留缺失用量的占用，避免以偏低费用释放预算。
        if (inputTokens == null || outputTokens == null) return null;
        BigDecimal input = BigDecimal.valueOf(inputTokens)
                .multiply(properties.getProcessing().getPlusInputUsdPerMillionTokens());
        BigDecimal output = BigDecimal.valueOf(outputTokens)
                .multiply(properties.getProcessing().getPlusOutputUsdPerMillionTokens());
        return input.add(output).divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception exception) {
            throw new SegmentReviewException("重点片段复核引用解析失败", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw new SegmentReviewException("重点复核结果缺少 " + field);
        }
        return value.asText().trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new SegmentReviewException("重点片段复核结果序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new SegmentReviewException("重点片段输入摘要生成失败", exception);
        }
    }

    private String seconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds).divide(BigDecimal.valueOf(1_000L), 3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private void copy(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // 进程结束或被终止时由主线程统一判断退出码。
        }
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理由下一次同任务执行再次兜底。
                }
            });
        } catch (IOException exception) {
            log.warn("重点片段复核临时目录清理失败 path={}", root);
        }
    }

    private String truncate(String message) {
        String safe = message == null || message.isBlank() ? "重点片段复核失败" : message;
        return safe.substring(0, Math.min(500, safe.length()));
    }

    public record Result(int selectedCount, int reviewedCount, int failedCount, BigDecimal actualCostUsd) {
    }

    private record SegmentRange(long startMs, long endMs) {
    }

    private record ParsedReview(boolean confirmed, String severity, BigDecimal confidence,
                                String description, String suggestedAction) {
    }

    public static class SegmentReviewException extends RuntimeException {
        public SegmentReviewException(String message) {
            super(message);
        }

        public SegmentReviewException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
