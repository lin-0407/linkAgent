package com.link.linkagent.creator.creation.voice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.service.AssetMediaPreparer;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Charge;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCompiler;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.voice.mapper.CreationVoiceMapper;
import com.link.linkagent.creator.creation.voice.model.CreationVoice;
import com.link.linkagent.creator.creation.voice.model.VoiceModels.Proposal;
import com.link.linkagent.creator.creation.voice.provider.SpeechSynthesisProvider;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 配音准备后台执行：只处理用户选择 TTS 的场景。
 * <p>
 * 合成与转写都是付费外部调用，必须在发送前预留并标记发送；返回后按实际用量结算，
 * 结果不明保留 UNKNOWN。音频落私有存储并复核摘要后才登记素材，最后按转写时间生成新故事板；
 * 音频放不进镜头或文稿对不上时只给提案，不生成不可渲染的新版本。
 */
@Service
@ConditionalOnProperty(prefix = "creator.creation.voice", name = "enabled", havingValue = "true")
public class CreationVoiceWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CreationVoiceWorker.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final int LEASE_SECONDS = 300;

    private final CreationVoiceMapper mapper;
    private final CreationVoicePersistence persistence;
    private final CreationVoiceProperties settings;
    private final CreationRuntimeService runtime;
    private final CreationBillingProperties billing;
    private final StoryboardMapper boards;
    private final StoryboardPersistence storyboards;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final AssetMediaPreparer preparer;
    private final ObjectStorageService storage;
    private final ObjectStorageProperties storageProperties;
    private final FixedProjectRendererProperties renderer;
    private final List<SpeechSynthesisProvider> providers;
    private final SpeechRecognitionProvider recognition;
    private final CreatorMediaProperties mediaProperties;
    private final VoiceScriptMatcher matcher;
    private final ObjectMapper json;

    private final String worker = "voice-" + UUID.randomUUID();
    private final AtomicBoolean busy = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("creation-voice-worker").factory());
    private final ScheduledExecutorService pulses = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("creation-voice-heartbeat").factory());

    public CreationVoiceWorker(CreationVoiceMapper mapper, CreationVoicePersistence persistence,
            CreationVoiceProperties settings, CreationRuntimeService runtime, CreationBillingProperties billing,
            StoryboardMapper boards,
            StoryboardPersistence storyboards, StoryboardCodec codec, StoryboardCompiler compiler,
            AssetMediaPreparer preparer, ObjectStorageService storage, ObjectStorageProperties storageProperties,
            FixedProjectRendererProperties renderer, List<SpeechSynthesisProvider> providers,
            SpeechRecognitionProvider recognition, CreatorMediaProperties mediaProperties,
            VoiceScriptMatcher matcher, ObjectMapper json) {
        this.mapper = mapper; this.persistence = persistence; this.settings = settings; this.runtime = runtime;
        this.billing = billing; this.boards = boards; this.storyboards = storyboards; this.codec = codec; this.compiler = compiler;
        this.preparer = preparer; this.storage = storage; this.storageProperties = storageProperties;
        this.renderer = renderer; this.providers = providers; this.recognition = recognition;
        this.mediaProperties = mediaProperties; this.matcher = matcher; this.json = json;
    }

    @Scheduled(fixedDelayString = "${creator.creation.voice.poll-interval-ms:2000}")
    public void poll() {
        if (stopping.get() || !busy.compareAndSet(false, true)) return;
        try {
            executor.submit(() -> {
                try {
                    mapper.terminalWithPending().forEach(ref -> persistence.closeRows(ref.runId(),
                            "CANCELLED".equals(ref.runStatus()) ? "CANCELLED" : "FAILED",
                            "CANCELLED".equals(ref.runStatus()) ? null : "VOICE_RUN_FAILED"));
                    for (var job : mapper.candidates()) { if (stopping.get()) break; process(job); }
                }
                catch (RuntimeException ex) { log.warn("配音扫描暂不可用，类型={}", ex.getClass().getSimpleName()); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException ex) { busy.set(false); if (!stopping.get()) log.warn("配音扫描暂不可用，类型={}", ex.getClass().getSimpleName()); }
    }

    /** 同步入口供受控测试推进同一数据库队列，不绕过领取、发送标记与租约围栏。 */
    public void process(CreationVoiceMapper.Job job) {
        Run run;
        try {
            // 扫描结果可能刚被取消或已结束，过期不是执行失败，也不能抛给调用方。
            run = runtime.claim(job.taskId(), job.ownerId(), job.runId(), worker, LEASE_SECONDS).orElse(null);
            if (run == null) return;
        } catch (ResponseStatusException exception) {
            return;
        }
        if ("CANCEL_REQUESTED".equals(run.status())) {
            persistence.closeRows(run.runId(), "CANCELLED", null);
            acknowledge(run);
            return;
        }
        var lost = new AtomicBoolean();
        var pulse = pulses.scheduleAtFixedRate(() -> {
            try {
                if (!runtime.heartbeat(run.taskId(), run.ownerId(), run.runId(), worker,
                        run.leaseGeneration(), LEASE_SECONDS)
                        || !"RUNNING".equals(runtime.getRun(run.taskId(), run.ownerId(), run.runId()).status()))
                    lost.set(true);
            } catch (RuntimeException ex) { lost.set(true); }
        }, 0, 2, TimeUnit.SECONDS);
        try {
            execute(job, run, lost);
        } catch (VoiceFailure failure) {
            log.info("配音准备未完成，运行={}，原因={}", run.runId(), failure.code);
        } catch (Exception exception) {
            log.warn("配音准备等待恢复，运行={}，类型={}", run.runId(), exception.getClass().getSimpleName());
        } finally {
            pulse.cancel(false);
        }
    }

    private void execute(CreationVoiceMapper.Job job, Run run, AtomicBoolean lost) {
        BooleanSupplier cancelled = () -> stopping.get() || lost.get() || Thread.currentThread().isInterrupted();
        // 上次进程可能在合成中途退出：已发送的场景不能重发，未发送的场景可以安全回到排队。
        if (recoverInterrupted(run)) {
            settleRunAsFailed(run, "VOICE_TTS_RESULT_UNKNOWN");
            return;
        }
        boolean failed = false;
        for (CreationVoice row : mapper.scenes(run.runId())) {
            if (cancelled.getAsBoolean()) break;
            if (Set.of("ALIGNED", "NEEDS_REVIEW", "FAILED", "CANCELLED").contains(row.getStatus())) continue;
            try {
                if ("QUEUED".equals(row.getStatus())) synthesize(run, row, cancelled);
                CreationVoice current = reload(row);
                if ("SYNTHESIZED".equals(current.getStatus()) || "TRANSCRIBING".equals(current.getStatus()))
                    transcribe(run, current, cancelled);
            } catch (VoiceFailure failure) {
                // 取消后不再把场景写成失败，运行收尾会把未完成场景标为取消。
                if (!cancelled.getAsBoolean()) markFailed(row, failure.code, run.leaseGeneration());
                failed = !cancelled.getAsBoolean();
                break;
            }
        }
        if (cancelled.getAsBoolean()) {
            if ("CANCEL_REQUESTED".equals(runtime.getRun(run.taskId(), run.ownerId(), run.runId()).status())) {
                persistence.closeRows(run.runId(), "CANCELLED", null);
                acknowledge(run);
            }
            return;
        }
        if (failed) {
            settleRunAsFailed(run);
            return;
        }
        List<CreationVoice> rows = mapper.scenes(run.runId());
        List<CreationVoice> unresolved = rows.stream()
                .filter(row -> !Set.of("ALIGNED", "NEEDS_REVIEW").contains(row.getStatus())).toList();
        if (!unresolved.isEmpty()) {
            markFailed(unresolved.getFirst(), "VOICE_PREPARATION_INCOMPLETE", run.leaseGeneration());
            settleRunAsFailed(run);
            return;
        }
        boolean blocked = rows.stream().anyMatch(row -> "NEEDS_REVIEW".equals(row.getStatus()));
        try {
            if (blocked) {
                runtime.releaseUnsent(run.taskId(), run.ownerId(), run.runId());
                runtime.complete(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(),
                        reviewResult(job, rows));
                return;
            }
            finishAligned(job, run, rows);
        } catch (VoiceFailure failure) {
            log.warn("配音收尾未通过，运行={}，原因={}", run.runId(), failure.code);
            try { settleRunAsFailed(run, failure.code); }
            catch (RuntimeException ignored) { log.info("配音失败收尾等待租约恢复，运行={}", run.runId()); }
        } catch (RuntimeException exception) {
            // 收尾失败不能把运行留在占用状态，否则后续扫描会反复领取同一失败结果。
            log.warn("配音收尾失败，运行={}，类型={}", run.runId(), exception.getClass().getSimpleName());
            try { settleRunAsFailed(run); }
            catch (RuntimeException ignored) { log.info("配音失败收尾等待租约恢复，运行={}", run.runId()); }
        }
    }

    private boolean recoverInterrupted(Run run) {
        for (CreationVoice row : mapper.scenes(run.runId())) {
            if (!"SYNTHESIZING".equals(row.getStatus())) continue;
            String key = ttsKey(run.runId(), row.getSceneId());
            var charge = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key);
            if (charge.isPresent() && charge.get().sentAt() != null) {
                runtime.markUnknown(run.taskId(), run.ownerId(), key);
                markFailed(row, "VOICE_TTS_RESULT_UNKNOWN", run.leaseGeneration());
                return true;
            }
            // 没有发送事实才能重排，避免把可能已经发出的调用再付一次。
            mapper.requeue(run.runId(), row.getSceneId());
        }
        return false;
    }

    // ---------------------------------------------------------------- 合成

    private void synthesize(Run run, CreationVoice row, BooleanSupplier cancelled) {
        var provider = providers.isEmpty() ? null : providers.getFirst();
        if (provider == null) throw new VoiceFailure("VOICE_PROVIDER_NOT_CONFIGURED");
        if (mapper.beginSynthesisWith(run.runId(), row.getSceneId(), provider.provider(), provider.model(),
                provider.voice(), worker, run.leaseGeneration()) != 1) return;
        // 供应商身份先落库，后续音频事实必须记录实际调用参数而不是排队时的空值。
        row = reload(row);
        String key = ttsKey(run.runId(), row.getSceneId());
        BigDecimal usd = BigDecimal.valueOf(row.getCharacters())
                .multiply(settings.getUsdPerMillionCharacters()).divide(MILLION, 8, RoundingMode.HALF_UP);
        String snapshot = priceSnapshot("VOICE_TTS", usd, Map.of("model", provider.model(), "voice", provider.voice(),
                "characters", row.getCharacters(), "usdPerMillionCharacters", settings.getUsdPerMillionCharacters().toPlainString()));
        Charge charge = beginCharge(run, key, "VOICE_TTS", cny(usd), snapshot);
        if (!send(run, charge, key)) throw new VoiceFailure("VOICE_TTS_NOT_SENT");
        SpeechSynthesisProvider.Audio audio;
        try {
            audio = provider.synthesize(row.getTextContent());
        } catch (com.link.linkagent.creator.creation.voice.provider.DashScopeCosyVoiceProvider.SynthesisRejectedException rejected) {
            // 明确的 4xx 拒绝可以确认没有生成音频，按 0 结算，不能把额度长期挂成未知。
            settle(run, key, BigDecimal.ZERO.setScale(6), "voice-tts-rejected-" + row.getSceneId());
            throw new VoiceFailure("VOICE_TTS_REJECTED");
        } catch (RuntimeException exception) {
            runtime.markUnknown(run.taskId(), run.ownerId(), key);
            throw new VoiceFailure("VOICE_TTS_RESULT_UNKNOWN");
        }
        // 合成按输入字符计费；供应商回报字符数优先，缺失时用实际提交的字符数，两者都不是伪造账单。
        long billed = audio.billedCharacters() == null ? row.getCharacters() : audio.billedCharacters();
        settle(run, key, amountFromCharge(run, key, BigDecimal.valueOf(billed), "usdPerMillionCharacters", true),
                "voice-tts-" + row.getSceneId());
        if (cancelled.getAsBoolean()) throw new VoiceFailure("VOICE_CANCELLED");
        if (!wav(audio.bytes())) throw new VoiceFailure("VOICE_FORMAT_UNSUPPORTED");
        persistAudio(run, row, audio, audio.requestId(), cancelled);
    }

    private void persistAudio(Run run, CreationVoice row, SpeechSynthesisProvider.Audio audio,
                              String requestId, BooleanSupplier cancelled) {
        Path work = null, output = null;
        List<String> uploaded = new ArrayList<>();
        String bucket = storageProperties.getBucket();
        if (bucket == null || bucket.isBlank()) throw new VoiceFailure("VOICE_STORAGE_NOT_CONFIGURED");
        try {
            Path root = Path.of(renderer.getWorkRoot()).toAbsolutePath().normalize().resolve("voice");
            Files.createDirectories(root);
            if (!root.toRealPath().equals(root)) throw new VoiceFailure("VOICE_WORKSPACE_INVALID");
            work = Files.createTempDirectory(root, row.getRunId() + "-" + row.getSceneId() + "-");
            Path source = work.resolve("source.wav");
            Files.write(source, audio.bytes());
            output = Files.createDirectory(work.resolve("prepared"));
            var prepared = preparer.prepare(source, output, "NARRATION", cancelled);
            if (prepared.durationMs() == null || prepared.durationMs() <= 0) throw new VoiceFailure("VOICE_AUDIO_INVALID");
            String originalSha = digest(source);
            String assetId = UUID.randomUUID().toString();
            String base = "users/" + row.getOwnerId() + "/tasks/" + row.getTaskId() + "/assets/" + assetId;
            String sourceKey = base + "/original/source.wav";
            String normalizedKey = base + "/prepared/voice/normalized.wav";
            Path normalized = prepared.files().stream().filter(file -> "NORMALIZED".equals(file.role()))
                    .findFirst().orElseThrow(() -> new VoiceFailure("VOICE_AUDIO_INVALID")).path();
            String normalizedSha = digest(normalized);
            check(cancelled);
            storage.putObject(bucket, sourceKey, source, "audio/wav", STORAGE_TIMEOUT);
            uploaded.add(sourceKey);
            storage.putObject(bucket, normalizedKey, normalized, "audio/wav", STORAGE_TIMEOUT);
            uploaded.add(normalizedKey);
            if (storage.headObject(bucket, sourceKey).contentLength() != Files.size(source)
                    || storage.headObject(bucket, normalizedKey).contentLength() != Files.size(normalized))
                throw new VoiceFailure("VOICE_STORAGE_MISMATCH");
            // 不能只信 PUT 成功，下载回来按完整摘要核对，避免把损坏音频写进素材事实。
            Path verify = work.resolve("verify.wav");
            storage.downloadObject(bucket, normalizedKey, verify, STORAGE_TIMEOUT);
            if (!normalizedSha.equals(digest(verify))) throw new VoiceFailure("VOICE_STORAGE_MISMATCH");
            ObjectNode metadata = json.createObjectNode();
            metadata.set("original", prepared.originalMetadata());
            metadata.set("normalized", prepared.normalizedMetadata());
            metadata.putObject("generated").put("kind", "TTS").put("provider", row.getProvider())
                    .put("model", row.getModel()).put("voice", row.getVoiceId())
                    .put("format", "wav").put("sampleRate", settings.getSampleRate())
                    .put("textSha256", row.getTextSha256()).put("characters", row.getCharacters())
                    .put("runId", row.getRunId()).put("sceneId", row.getSceneId())
                    .put("storyboardId", row.getStoryboardId());
            ArrayNode objects = json.createArrayNode();
            objects.addObject().put("role", "NORMALIZED").put("objectKey", normalizedKey)
                    .put("contentType", "audio/wav").put("bytes", Files.size(normalized)).put("sha256", normalizedSha);
            var record = new CreationVoicePersistence.GeneratedAudio(bucket, assetId,
                    "voice-" + row.getSceneId() + ".wav", Files.size(source), originalSha, sourceKey,
                    Files.size(normalized), normalizedSha, prepared.durationMs(), codec.write(metadata),
                    codec.write(objects));
            check(cancelled);
            persistence.saveAudio(row, worker, run.leaseGeneration(), requestId, record);
            uploaded.clear();
            CreationVoice saved = reload(row);
            long sceneMs = quantizedSceneMs(row.getSceneDurationMs());
            if (prepared.durationMs() > sceneMs + 1) {
                long proposed = quantize(prepared.durationMs());
                review(saved, "VOICE_AUDIO_TOO_LONG", new Proposal(saved.getSceneId(), "EXTEND_SCENE",
                        "实际配音比场景长 " + (prepared.durationMs() - sceneMs)
                                + " 毫秒；请延长场景并重新确认全片时长，或缩短文稿后重新生成", proposed), null, null,
                        run.leaseGeneration());
            }
        } catch (VoiceFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new VoiceFailure("VOICE_STORAGE_UNAVAILABLE");
        } finally {
            discardQuietly(uploaded, storageProperties.getBucket());
            deleteQuietly(work);
            deleteQuietly(output == null ? null : output.getParent());
        }
    }

    // ---------------------------------------------------------------- 转写与对齐

    private void transcribe(Run run, CreationVoice row, BooleanSupplier cancelled) {
        CreationAsset asset = persistence.assetByKey(row.getTaskId(), row.getOwnerId(), run.runId(), row.getSceneId());
        if (asset == null || row.getAudioAssetId() == null) throw new VoiceFailure("VOICE_AUDIO_MISSING");
        String key = asrKey(run.runId(), row.getSceneId());
        if (row.getAudioDurationMs() == null || row.getAudioDurationMs() <= 0)
            throw new VoiceFailure("VOICE_AUDIO_MISSING");
        if (row.getProviderTaskId() == null) {
            long seconds = Math.max(1, (row.getAudioDurationMs() + 999) / 1000);
            BigDecimal usd = mediaProperties.getProcessing().getAsrUsdPerSecond()
                    .multiply(BigDecimal.valueOf(seconds));
            String snapshot = priceSnapshot("VOICE_ASR", usd, Map.of("model", mediaProperties.getPreflight().getAsrModel(),
                    "seconds", seconds, "usdPerSecond", mediaProperties.getProcessing().getAsrUsdPerSecond().toPlainString()));
            // 短签在本地生成，失败时还没有发送，必须先于预留和发送标记处理。
            PresignedObjectRead read;
            try {
                read = storage.presignAsrGetObject(asset.getBucketName(),
                        persistence.asset(asset).objectKey(), mediaProperties.getProcessing().getProviderReadTtl());
            } catch (RuntimeException exception) {
                throw new VoiceFailure("VOICE_ASR_URL_UNAVAILABLE");
            }
            Charge charge = beginCharge(run, key, "VOICE_ASR", cny(usd), snapshot);
            if (!send(run, charge, key)) throw new VoiceFailure("VOICE_ASR_NOT_SENT");
            String providerTaskId;
            try {
                providerTaskId = recognition.submit(read.url());
            } catch (RuntimeException exception) {
                runtime.markUnknown(run.taskId(), run.ownerId(), key);
                throw new VoiceFailure("VOICE_ASR_SUBMIT_UNCERTAIN");
            }
            try {
                persistence.transcribing(row, providerTaskId, worker, run.leaseGeneration());
            } catch (RuntimeException exception) {
                runtime.markUnknown(run.taskId(), run.ownerId(), key);
                throw new VoiceFailure("VOICE_ASR_SUBMIT_UNCERTAIN");
            }
            row.setProviderTaskId(providerTaskId);
            row.setStatus("TRANSCRIBING");
        }
        long deadline = System.nanoTime() + settings.getTranscribeTimeout().toNanos();
        while (true) {
            check(cancelled);
            SpeechRecognitionProvider.QueryResult result;
            try { result = recognition.query(row.getProviderTaskId()); }
            catch (RuntimeException exception) { throw new VoiceFailure("VOICE_ASR_QUERY_FAILED"); }
            switch (result.status()) {
                case SUCCEEDED -> {
                    SpeechRecognitionProvider.TranscriptionResult transcript;
                    try { transcript = recognition.loadResult(result.transcriptionUrl()); }
                    catch (RuntimeException exception) { throw new VoiceFailure("VOICE_ASR_RESULT_INVALID"); }
                    settleUsage(run, key, result.usageSeconds(), "voice-asr-" + row.getSceneId());
                    align(row, transcript, run.leaseGeneration());
                    return;
                }
                case NO_SPEECH -> {
                    settleUsage(run, key, result.usageSeconds(), "voice-asr-" + row.getSceneId());
                    review(row, "VOICE_AUDIO_NO_SPEECH", new Proposal(row.getSceneId(), "VOICE_REGENERATE",
                            "实际音频没有识别到语音；请检查文稿或音色后重新生成", null), null, null,
                            run.leaseGeneration());
                    return;
                }
                case FAILED -> {
                    settleUsage(run, key, result.usageSeconds(), "voice-asr-" + row.getSceneId());
                    throw new VoiceFailure("VOICE_ASR_FAILED");
                }
                default -> { }
            }
            if (System.nanoTime() >= deadline) {
                // 已提交但结果未回，费用未知；保留任务标识，但不在这里自动重发。
                runtime.markUnknown(run.taskId(), run.ownerId(), key);
                throw new VoiceFailure("VOICE_ASR_TIMEOUT");
            }
            sleep(settings.getTranscribePollInterval());
        }
    }

    private void align(CreationVoice row, SpeechRecognitionProvider.TranscriptionResult transcript, long generation) {
        long sceneDuration = row.getSceneDurationMs(), audioDuration = row.getAudioDurationMs() == null ? 0 : row.getAudioDurationMs();
        var match = matcher.compare(row.getTextContent(), transcript.segments(), audioDuration, sceneDuration, 100);
        ObjectNode payload = json.createObjectNode();
        payload.put("expected", row.getTextContent()).put("transcript", match.transcript());
        ArrayNode segments = payload.putArray("segments");
        transcript.segments().stream().limit(200).forEach(segment -> segments.addObject()
                .put("startMs", segment.startMs()).put("endMs", segment.endMs()).put("text", segment.text())
                .put("confidence", segment.confidence() == null ? null : segment.confidence())
                .put("speaker", segment.speaker()));
        ArrayNode cues = payload.putArray("cues");
        match.cues().forEach(cue -> cues.addObject().put("startMs", cue.startMs()).put("endMs", cue.endMs()).put("text", cue.text()));
        BigDecimal ratio = BigDecimal.valueOf(match.ratio()).setScale(5, RoundingMode.HALF_UP);
        if (match.problemCode() != null) {
            review(row, match.problemCode(), new Proposal(row.getSceneId(), "VOICE_REGENERATE",
                    match.message(), null), codec.write(payload), ratio, generation);
            return;
        }
        if (ratio.compareTo(settings.getMatchThreshold()) < 0) {
            review(row, "VOICE_SCRIPT_MISMATCH", new Proposal(row.getSceneId(), "VOICE_SCRIPT_CONFIRM",
                    "转写文本与场景文稿相似度 " + ratio.toPlainString() + "，低于 " + settings.getMatchThreshold()
                            + "；请确认配音是否念对，或调整文稿后重新生成", null), codec.write(payload), ratio, generation);
            return;
        }
        persistence.aligned(row, "ALIGNED", codec.write(payload), ratio, null, null, worker, generation);
        row.setStatus("ALIGNED");
        row.setTranscriptJson(codec.write(payload));
        row.setMatchRatio(ratio);
    }

    private void review(CreationVoice row, String code, Proposal proposal, String transcriptJson, BigDecimal ratio,
                        long generation) {
        String proposals = codec.write(List.of(proposal));
        persistence.aligned(row, "NEEDS_REVIEW", transcriptJson, ratio, proposals, code, worker, generation);
        row.setStatus("NEEDS_REVIEW");
    }

    // ---------------------------------------------------------------- 收尾

    private void finishAligned(CreationVoiceMapper.Job job, Run run, List<CreationVoice> rows) {
        var board = boards.board(job.taskId(), job.ownerId(), job.storyboardId())
                .orElseThrow(() -> new VoiceFailure("VOICE_BOARD_MISSING"));
        Candidate source = codec.candidate(codec.read(board.documentJson()).path("candidate"));
        List<Scene> scenes = new ArrayList<>();
        List<Asset> extra = new ArrayList<>();
        Set<String> extraIds = new HashSet<>();
        for (Scene scene : source.scenes()) {
            if (!"TTS".equals(scene.audioMode())) { scenes.add(scene); continue; }
            CreationVoice row = rows.stream().filter(item -> item.getSceneId().equals(scene.id())).findFirst()
                    .orElseThrow(() -> new VoiceFailure("VOICE_SCENE_MISSING"));
            CreationAsset asset = persistence.assetByKey(job.taskId(), job.ownerId(), run.runId(), row.getSceneId());
            if (asset == null || row.getAudioAssetId() == null) throw new VoiceFailure("VOICE_AUDIO_MISSING");
            extra.add(persistence.asset(asset));
            extraIds.add(asset.getAssetId());
            scenes.add(new Scene(scene.id(), scene.layout(), scene.title(), scene.body(), scene.narration(),
                    scene.durationMs(), scene.visualAssetId(), scene.sourceInMs(), scene.sourceOutMs(), scene.fit(),
                    scene.audioMode(), asset.getAssetId(), cues(row), scene.sourceRefs()));
        }
        Candidate candidate = new Candidate(List.copyOf(scenes));
        var brief = storyboards.brief(job.taskId(), job.ownerId(), job.briefId());
        var frozen = new ArrayList<>(storyboards.snapshotAssets(job.taskId(), job.ownerId(), briefAssetIds(brief)));
        for (Asset asset : extra) if (frozen.stream().noneMatch(item -> item.assetId().equals(asset.assetId()))) frozen.add(asset);
        var check = compiler.check(job.storyboardId(), codec.read(brief.documentJson()), candidate, frozen);
        if (!check.renderable()) {
            log.warn("配音故事板仍不可渲染，运行={}，问题={}", run.runId(),
                    check.problems().stream().map(Problem::code).toList());
            throw new VoiceFailure("VOICE_BOARD_NOT_RENDERABLE");
        }
        Board saved;
        try {
            saved = persistence.saveBoard(job, candidate, List.copyOf(extra), extraIds, worker,
                    run.leaseGeneration(), "voice-" + run.runId());
        } catch (ResponseStatusException exception) {
            throw new VoiceFailure("VOICE_BOARD_SAVE_FAILED");
        }
        runtime.complete(job.taskId(), job.ownerId(), run.runId(), worker, run.leaseGeneration(),
                boardResult(job, saved, rows));
    }

    private void settleRunAsFailed(Run run) { settleRunAsFailed(run, "VOICE_PREPARATION_FAILED"); }

    private void settleRunAsFailed(Run run, String code) {
        runtime.releaseUnsent(run.taskId(), run.ownerId(), run.runId());
        runtime.fail(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(), code);
    }

    private void markFailed(CreationVoice row, String code, long generation) {
        try { persistence.fail(row, code, worker, generation); }
        catch (RuntimeException exception) { log.info("配音失败结果等待租约恢复，场景={}", row.getSceneId()); }
    }

    private void acknowledge(Run run) {
        try { runtime.acknowledgeCancellation(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration()); }
        catch (RuntimeException exception) { log.info("配音取消等待原运行收尾，运行={}", run.runId()); }
    }

    private String boardResult(CreationVoiceMapper.Job job, Board board, List<CreationVoice> rows) {
        ObjectNode result = json.createObjectNode();
        result.put("storyboardId", board.storyboardId()).put("sourceStoryboardId", job.storyboardId());
        result.put("sceneCount", rows.size()).put("cueCount", rows.stream()
                .mapToInt(row -> cues(row).size()).sum());
        rows.forEach(row -> result.withArray("voices").addObject().put("sceneId", row.getSceneId())
                .put("audioAssetId", row.getAudioAssetId()).put("durationMs", row.getAudioDurationMs())
                .put("matchRatio", row.getMatchRatio()));
        return codec.write(result);
    }

    private String reviewResult(CreationVoiceMapper.Job job, List<CreationVoice> rows) {
        ObjectNode result = json.createObjectNode();
        result.put("sourceStoryboardId", job.storyboardId());
        ArrayNode proposals = result.putArray("proposals");
        for (CreationVoice row : rows) {
            if (row.getProposalJson() == null) continue;
            try { json.readTree(row.getProposalJson()).forEach(proposals::add); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) { log.info("配音提案无法读取，场景={}", row.getSceneId()); }
        }
        rows.forEach(row -> result.withArray("voices").addObject().put("sceneId", row.getSceneId())
                .put("status", row.getStatus()).put("audioAssetId", row.getAudioAssetId())
                .put("durationMs", row.getAudioDurationMs()));
        return codec.write(result);
    }

    private List<Cue> cues(CreationVoice row) {
        if (row.getTranscriptJson() == null) return List.of();
        try {
            List<Cue> cues = new ArrayList<>();
            for (JsonNode node : json.readTree(row.getTranscriptJson()).path("cues"))
                cues.add(new Cue(node.path("startMs").asLong(), node.path("endMs").asLong(), node.path("text").asText()));
            return List.copyOf(cues);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return List.of(); }
    }

    private List<String> briefAssetIds(Brief brief) {
        List<String> ids = new ArrayList<>();
        codec.read(brief.documentJson()).path("assetIds").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    // ---------------------------------------------------------------- 费用

    private Charge reserve(Run run, String key, String capability, BigDecimal amount, String snapshot) {
        try {
            return runtime.reserve(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(),
                    key, capability, amount, snapshot);
        } catch (ResponseStatusException exception) {
            throw new VoiceFailure("VOICE_BUDGET_BLOCKED");
        }
    }

    /** 领取新执行代时未发送的预留会被释放；确认从未发送后重新占用，已发送的调用绝不重发。 */
    /** 只有第一次发送返回 true；已发送的调用不能重发。 */
    private boolean send(Run run, Charge charge, String key) {
        if (charge.sentAt() != null) return false;
        try { return runtime.markSent(run.taskId(), run.ownerId(), key, worker, run.leaseGeneration()); }
        catch (ResponseStatusException exception) { return false; }
    }

    /**
     * 领取执行时会把未发送的预留释放为 RELEASED。只有确认从未发送才重新占用，
     * 已发送但没有结果的调用保持 UNKNOWN，绝不重发。
     */
    private Charge beginCharge(Run run, String key, String capability, BigDecimal amount, String snapshot) {
        var existing = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key);
        if (existing.isPresent() && existing.get().sentAt() != null) {
            runtime.markUnknown(run.taskId(), run.ownerId(), key);
            throw new VoiceFailure("VOICE_TTS".equals(capability) ? "VOICE_TTS_RESULT_UNKNOWN" : "VOICE_ASR_RESULT_UNKNOWN");
        }
        if (existing.isPresent() && "RELEASED".equals(existing.get().status())) {
            try { runtime.rearm(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(), key); }
            catch (ResponseStatusException exception) { throw new VoiceFailure("VOICE_BUDGET_BLOCKED"); }
        }
        return reserve(run, key, capability, amount, snapshot);
    }

    private void settle(Run run, String key, BigDecimal amount, String evidence) {
        runtime.settle(run.taskId(), run.ownerId(), key, amount, "USAGE_ESTIMATE", evidence);
    }

    private void settleUsage(Run run, String key, Long seconds, String evidence) {
        if (seconds == null) { runtime.markUnknown(run.taskId(), run.ownerId(), key); return; }
        settle(run, key, amountFromCharge(run, key, BigDecimal.valueOf(Math.max(0, seconds)), "usdPerSecond", false),
                evidence);
    }

    /** 结算沿用预留时的价格快照，重启或改配置都不能改变同一物理调用的金额依据。 */
    private BigDecimal amountFromCharge(Run run, String key, BigDecimal units, String unitField, boolean perMillion) {
        var charge = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key)
                .orElseThrow(() -> new VoiceFailure("VOICE_CHARGE_MISSING"));
        try {
            var price = json.readTree(charge.priceSnapshotJson());
            BigDecimal unit = new BigDecimal(price.path(unitField).asText());
            BigDecimal rate = new BigDecimal(price.path("usdToCny").asText());
            BigDecimal usd = perMillion ? units.multiply(unit).divide(MILLION, 8, RoundingMode.HALF_UP)
                    : units.multiply(unit);
            return cny(usd, rate);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new VoiceFailure("VOICE_PRICE_SNAPSHOT_INVALID");
        }
    }

    private BigDecimal cny(BigDecimal usd) { return cny(usd, billing.getUsdToCny()); }

    private BigDecimal cny(BigDecimal usd, BigDecimal rate) {
        BigDecimal value = usd.multiply(rate).setScale(6, RoundingMode.HALF_UP);
        return value.signum() == 0 ? new BigDecimal("0.000001") : value;
    }

    private BigDecimal mediaBillingRate() { return billing.getUsdToCny(); }

    private String priceSnapshot(String capability, BigDecimal usd, Map<String, Object> extra) {
        ObjectNode node = json.createObjectNode();
        node.put("currency", "USD").put("usdUpperBound", usd.toPlainString())
                .put("usdToCny", mediaBillingRate().toPlainString())
                .put("capability", capability).put("basis", "CONFIGURED_ESTIMATE").put("accounting", "USAGE_ESTIMATE");
        extra.forEach((name, value) -> node.put(name, String.valueOf(value)));
        return node.toString();
    }

    // ---------------------------------------------------------------- 杂项

    private CreationVoice reload(CreationVoice row) {
        return mapper.scene(row.getRunId(), row.getSceneId()).orElse(row);
    }

    private boolean wav(byte[] bytes) {
        return bytes != null && bytes.length > 44 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    private static long quantize(long millis) { return (long) Math.ceil(Math.ceil(millis * 30 / 1000.0) * 1000 / 30); }

    /** 场景真实帧长按 30 fps 量化后再比较音频，避免 +1ms 落在帧边界上误判超长。 */
    private static long quantizedSceneMs(long millis) {
        return Math.round(Math.round(millis * 30.0 / 1000) * 1000.0 / 30);
    }

    private static String digest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void sleep(Duration duration) {
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new VoiceFailure("VOICE_INTERRUPTED"); }
    }

    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new VoiceFailure("VOICE_CANCELLED"); }

    private void discardQuietly(List<String> keys, String bucket) {
        for (String key : keys) try { storage.deleteObject(bucket, key); }
        catch (RuntimeException exception) { log.warn("配音对象等待后续清理，对象={}", key); }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        } catch (Exception ignored) { }
    }

    private static String ttsKey(String runId, String sceneId) { return "voice-tts-" + runId + "-" + sceneId; }
    private static String asrKey(String runId, String sceneId) { return "voice-asr-" + runId + "-" + sceneId; }

    private static final class VoiceFailure extends RuntimeException {
        private final String code;
        VoiceFailure(String code) { super(code); this.code = code; }
    }

    @Override @PreDestroy
    public void close() {
        stopping.set(true);
        executor.shutdownNow(); pulses.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
