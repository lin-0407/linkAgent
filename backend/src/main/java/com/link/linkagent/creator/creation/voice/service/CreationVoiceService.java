package com.link.linkagent.creator.creation.voice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.RunView;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.voice.model.CreationVoice;
import com.link.linkagent.creator.creation.voice.model.VoiceModels;
import com.link.linkagent.creator.creation.voice.model.VoiceModels.*;
import com.link.linkagent.creator.creation.voice.provider.SpeechSynthesisProvider;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.digest;

/** 配音准备只处理简报选择 TTS 的场景，估算与授权先行，未配置供应商时保持只读说明。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class CreationVoiceService {
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final StoryboardMapper boards;
    private final StoryboardPersistence persistence;
    private final StoryboardCodec codec;
    private final MediaUploadMapper media;
    private final CreationVoicePersistence voices;
    private final CreationRuntimeService runtime;
    private final CreationVoiceProperties settings;
    private final CreationBillingProperties billing;
    private final CreatorMediaProperties mediaProperties;
    private final List<SpeechSynthesisProvider> providers;
    private final ObjectMapper json;

    public CreationVoiceService(StoryboardMapper boards, StoryboardPersistence persistence, StoryboardCodec codec,
            MediaUploadMapper media, CreationVoicePersistence voices, CreationRuntimeService runtime,
            CreationVoiceProperties settings, CreationBillingProperties billing, CreatorMediaProperties mediaProperties,
            List<SpeechSynthesisProvider> providers, ObjectMapper json) {
        this.boards = boards; this.persistence = persistence; this.codec = codec; this.media = media;
        this.voices = voices; this.runtime = runtime; this.settings = settings; this.billing = billing;
        this.mediaProperties = mediaProperties; this.providers = providers; this.json = json;
    }

    public Estimate estimate(String task, String owner, String boardId) {
        var context = context(task, owner, boardId);
        var blockers = new ArrayList<String>();
        var providerValue = providers.isEmpty() ? null : providers.getFirst();
        boolean keyConfigured = mediaProperties.getPreflight().getDashScopeApiKey() != null
                && !mediaProperties.getPreflight().getDashScopeApiKey().isBlank();
        if (!settings.configured() || providerValue == null || !keyConfigured)
            blockers.add("配音供应商尚未配置：需要 creator.creation.voice.enabled=true、模型与音色，以及 DashScope 密钥");
        if (!"TTS".equals(context.dialogueMode()))
            blockers.add("本简报的声音选择不是 TTS，不能只对部分场景生成配音");
        if (context.scenes().isEmpty()) blockers.add("本故事板没有待配音的 TTS 场景");
        if (context.scenes().size() > settings.getMaxScenes()) blockers.add("待配音场景超过单次受理上限");
        List<SceneEstimate> scenes = new ArrayList<>();
        long totalCharacters = 0;
        BigDecimal totalCny = BigDecimal.ZERO;
        for (Scene scene : context.scenes()) {
            // 文稿原样保存，摘要必须和编译器看到的 narrations 完全一致。
            String text = Objects.toString(scene.narration(), "");
            int characters = text.codePointCount(0, text.length());
            if (characters < 1 || characters > settings.getMaxSceneCharacters()) {
                blockers.add("场景 " + scene.id() + " 的文稿为空或超过单场景字符上限");
                continue;
            }
            BigDecimal ttsUsd = BigDecimal.valueOf(characters).multiply(settings.getUsdPerMillionCharacters())
                    .divide(MILLION, 8, RoundingMode.HALF_UP);
            // 音频放不进镜头时不会提交转写，场景帧长就是音频秒数的保守上界。
            long seconds = Math.max(1, (scene.durationMs() + 999) / 1000);
            BigDecimal asrUsd = mediaProperties.getProcessing().getAsrUsdPerSecond().multiply(BigDecimal.valueOf(seconds));
            totalCharacters += characters;
            totalCny = totalCny.add(toCny(ttsUsd)).add(toCny(asrUsd));
            scenes.add(new SceneEstimate(scene.id(), characters, StoryboardCodec.digest(text), scene.durationMs(), ttsUsd, asrUsd));
        }
        return new Estimate(boardId, context.briefId(), settings.configured() && providerValue != null && keyConfigured,
                settings.getModel(), settings.getVoice(), mediaProperties.getPreflight().getAsrModel(),
                context.scenes().size(), totalCharacters, billing.getUsdToCny(), totalCny, "CONFIGURED_ESTIMATE",
                List.copyOf(scenes), List.copyOf(blockers));
    }

    public Preparation generate(String task, String owner, String key, String boardId, VoiceModels.Generate input) {
        if (key == null || key.isBlank() || key.length() > 128) throw conflict("配音受理需要非空幂等键，最多 128 字符");
        var estimate = estimate(task, owner, boardId);
        if (!estimate.configured()) throw conflict("配音供应商尚未配置，不能发起付费调用");
        if (!estimate.blockers().isEmpty()) throw conflict(estimate.blockers().getFirst());
        if (input.maxAdditionalCostCny().compareTo(estimate.estimatedMaxAdditionalCostCny()) < 0)
            throw conflict("本次授权低于配音费用估算，请先查询估算并确认限额");
        var context = context(task, owner, boardId);
        var request = json.createObjectNode();
        request.put("purpose", "VOICE").put("storyboardId", boardId).put("briefId", context.briefId());
        var scenes = request.putArray("scenes");
        var rows = new ArrayList<CreationVoice>();
        int position = 0;
        for (Scene scene : context.scenes()) {
            String text = scene.narration();
            String sha = StoryboardCodec.digest(text);
            scenes.addObject().put("sceneId", scene.id()).put("textSha256", sha)
                    .put("characters", text.codePointCount(0, text.length())).put("sceneDurationMs", scene.durationMs());
            var row = new CreationVoice();
            row.setSceneId(scene.id()); row.setTaskId(task); row.setOwnerId(owner);
            row.setBriefId(context.briefId()); row.setStoryboardId(boardId); row.setPosition(position++);
            row.setTextContent(text); row.setTextSha256(sha);
            row.setCharacters(text.codePointCount(0, text.length())); row.setSceneDurationMs(scene.durationMs());
            rows.add(row);
        }
        var price = request.putObject("price");
        price.put("model", settings.getModel()).put("voice", settings.getVoice())
                .put("usdPerMillionCharacters", settings.getUsdPerMillionCharacters())
                .put("asrModel", mediaProperties.getPreflight().getAsrModel())
                .put("asrUsdPerSecond", mediaProperties.getProcessing().getAsrUsdPerSecond())
                .put("usdToCny", billing.getUsdToCny())
                .put("reservationCny", estimate.estimatedMaxAdditionalCostCny())
                .put("basis", "CONFIGURED_ESTIMATE");
        request.set("assets", json.valueToTree(context.frozen()));
        String payload = codec.write(request);
        // 请求摘要包含目标版本、修订和金额；同键换故事板或改限额必须拒绝。
        String requestHash = codec.hash(Map.of("storyboardId", boardId, "briefId", context.briefId(),
                "expectedWorkspaceRevision", input.expectedWorkspaceRevision(),
                "maxAdditionalCostCny", input.maxAdditionalCostCny().stripTrailingZeros(),
                "scenes", scenes.toString()));
        var started = voices.start(task, owner, key, "voice-" + digest(key), boardId, context.briefId(),
                requestHash, payload, input.maxAdditionalCostCny(), input.expectedWorkspaceRevision(), rows);
        return view(task, owner, started.runId());
    }

    public Preparation view(String task, String owner, String runId) {
        requireTask(task, owner);
        var job = voices.job(task, owner, runId);
        RunView run = runtime.getRun(task, owner, runId);
        var scenes = new ArrayList<SceneView>();
        var proposals = new ArrayList<Proposal>();
        for (CreationVoice row : voices.scenes(runId)) {
            String transcript = transcriptText(row.getTranscriptJson());
            List<Proposal> own = proposals(row.getProposalJson());
            proposals.addAll(own);
            scenes.add(new SceneView(row.getSceneId(), row.getPosition(), row.getStatus(), row.getCharacters(),
                    row.getSceneDurationMs(), row.getAudioAssetId(), row.getAudioDurationMs(), row.getAudioSha256(),
                    row.getMatchRatio(), row.getErrorCode(), row.getTextContent(),
                    transcript == null ? null : transcript.substring(0, Math.min(500, transcript.length())), List.copyOf(own)));
        }
        return new Preparation(runId, run.status(), run.stage(), job.storyboardId(), job.storyboardResultId(),
                !proposals.isEmpty(), List.copyOf(scenes), List.copyOf(proposals));
    }

    private String transcriptText(String transcriptJson) {
        if (transcriptJson == null) return null;
        try { return json.readTree(transcriptJson).path("transcript").asText(null); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return null; }
    }

    private List<Proposal> proposals(String value) {
        if (value == null) return List.of();
        try { return json.readValue(value, new TypeReference<List<Proposal>>() { }); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return List.of(); }
    }

    private BigDecimal toCny(BigDecimal usd) {
        BigDecimal cny = usd.multiply(billing.getUsdToCny()).setScale(6, RoundingMode.CEILING);
        // 台账不接受零金额预留；极小上界按最小可记账金额保留，结算后按实际费用回落。
        return cny.signum() == 0 ? new BigDecimal("0.000001") : cny;
    }

    private record Context(String briefId, String dialogueMode, List<Scene> scenes, List<Asset> frozen, JsonNode brief) { }

    private Context context(String task, String owner, String boardId) {
        requireTask(task, owner);
        var board = boards.board(task, owner, boardId).orElseThrow(() -> conflict("故事板不存在或不属于本任务"));
        var brief = persistence.brief(task, owner, board.briefId());
        JsonNode briefDocument = codec.read(brief.documentJson());
        Candidate candidate = codec.candidate(codec.read(board.documentJson()).path("candidate"));
        List<Scene> scenes = candidate.scenes().stream().filter(scene -> "TTS".equals(scene.audioMode())).toList();
        return new Context(brief.briefId(), briefDocument.path("dialogueMode").asText(), scenes,
                persistence.frozen(board), briefDocument);
    }

    private void requireTask(String task, String owner) {
        if (media.countTaskByOwner(task, owner) != 1) throw conflict("创作任务不存在");
    }
}
