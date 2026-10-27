package com.link.linkagent.creator.creation.aivideo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.aivideo.mapper.CreationVideoMapper;
import com.link.linkagent.creator.creation.aivideo.model.CreationVideo;
import com.link.linkagent.creator.creation.aivideo.model.VideoModels;
import com.link.linkagent.creator.creation.aivideo.model.VideoModels.*;
import com.link.linkagent.creator.creation.aivideo.provider.VideoGenerationProvider;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.RunView;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.service.CreationCapabilityGate;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.digest;

/**
 * AI 视频生成只处理用户明确要求生成画面的镜头。
 * <p>
 * 估算与授权先行：未配置供应商时保持只读说明；只有明确授权后才提交付费任务。
 * 素材合成镜头不会因为存在该能力而自动切换到付费生成。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class CreationVideoService {
    private static final String PROTOCOL = "async-task.v1";

    private final StoryboardMapper boards;
    private final StoryboardPersistence persistence;
    private final StoryboardCodec codec;
    private final MediaUploadMapper media;
    private final CreationVideoPersistence videos;
    private final CreationRuntimeService runtime;
    private final CreationVideoProperties settings;
    private final CreationBillingProperties billing;
    private final List<VideoGenerationProvider> providers;
    private final CreationCapabilityGate capabilities;
    private final BillingExchangeRate rates;
    private final ObjectMapper json;

    /** 测试和旧调用入口：默认全部能力开启，汇率取部署配置值。 */
    public CreationVideoService(StoryboardMapper boards, StoryboardPersistence persistence, StoryboardCodec codec,
            MediaUploadMapper media, CreationVideoPersistence videos, CreationRuntimeService runtime,
            CreationVideoProperties settings, CreationBillingProperties billing,
            List<VideoGenerationProvider> providers, ObjectMapper json) {
        this(boards, persistence, codec, media, videos, runtime, settings, billing, providers, json,
                CreationCapabilityGate.defaults(false, settings.isEnabled()), BillingExchangeRate.fixed(billing.getUsdToCny()));
    }

    @Autowired
    public CreationVideoService(StoryboardMapper boards, StoryboardPersistence persistence, StoryboardCodec codec,
            MediaUploadMapper media, CreationVideoPersistence videos, CreationRuntimeService runtime,
            CreationVideoProperties settings, CreationBillingProperties billing,
            List<VideoGenerationProvider> providers, ObjectMapper json, CreationCapabilityGate capabilities,
            BillingExchangeRate rates) {
        this.boards = boards; this.persistence = persistence; this.codec = codec; this.media = media;
        this.videos = videos; this.runtime = runtime; this.settings = settings; this.billing = billing;
        this.providers = providers; this.capabilities = capabilities; this.rates = rates; this.json = json;
    }

    public Capability capability() {
        var providerValue = provider();
        var blockers = configurationBlockers(providerValue);
        BigDecimal rate = rates.current().usdToCny();
        return new Capability(blockers.isEmpty(), settings.getProvider(), settings.getModel(), settings.durations(),
                settings.getWidth(), settings.getHeight(), settings.getUsdPerSecond(), rate,
                "CONFIGURED_ESTIMATE", PROTOCOL, List.copyOf(blockers));
    }

    public Estimate estimate(String task, String owner, String boardId, String scope, boolean regenerate) {
        return estimate(task, owner, boardId, scope, regenerate, null);
    }

    public Estimate estimate(String task, String owner, String boardId, String scope, boolean regenerate, List<String> sceneIds) {
        var plan = plan(task, owner, boardId, scope, regenerate, sceneIds);
        return plan.estimate();
    }

    public Preparation generate(String task, String owner, String key, String boardId, VideoModels.Generate input) {
        if (key == null || key.isBlank() || key.length() > 128) throw conflict("视频生成受理需要非空幂等键，最多 128 字符");
        var plan = plan(task, owner, boardId, input.scope(), input.regenerateAll(), input.sceneIds());
        var estimate = plan.estimate();
        // 先返回具体原因：关闭开关时不能误报成“供应商未配置”。
        if (!estimate.blockers().isEmpty()) throw conflict(estimate.blockers().getFirst());
        if (!estimate.configured()) throw conflict("视频生成供应商尚未配置，不能发起付费调用");
        if (input.maxAdditionalCostCny().compareTo(estimate.estimatedMaxCostCny()) < 0)
            throw conflict("本次授权低于视频生成费用估算，请先查询估算并确认限额");

        var context = plan.context();
        var request = json.createObjectNode();
        request.put("purpose", "VIDEO").put("storyboardId", boardId).put("briefId", context.briefId())
                .put("scope", input.scope()).put("regenerate", input.regenerateAll());
        var scenes = request.putArray("scenes");
        var fingerprint = new ArrayList<Map<String, Object>>();
        for (ScenePlan scene : estimate.scenes()) {
            scenes.addObject().put("sceneId", scene.sceneId()).put("action", scene.action())
                    .put("switchesToAi", scene.switchesToAi()).put("promptSha256", scene.promptSha256())
                    .put("sceneDurationMs", scene.durationMs())
                    .put("requestedSeconds", scene.requestedSeconds() == null ? 0 : scene.requestedSeconds())
                    .put("reuseAssetId", scene.reuseAssetId());
            fingerprint.add(Map.of("sceneId", scene.sceneId(), "promptSha256", scene.promptSha256(),
                    "action", scene.action(), "durationMs", scene.durationMs(),
                    "reuse", Objects.toString(scene.reuseAssetId(), "")));
        }
        var price = request.putObject("price");
        price.put("providerConfigurationHash",com.link.linkagent.creator.creation.execution.ProviderConfiguration.videoFingerprint(settings));
        price.put("provider", estimate.provider());
        price.put("model", estimate.model());
        price.put("usdPerSecond", estimate.usdPerSecond());
        price.put("usdToCny", estimate.usdToCny());
        price.set("durationsSeconds", json.valueToTree(estimate.durationsSeconds()));
        price.put("reservationCny", estimate.estimatedMaxCostCny());
        price.put("basis", "CONFIGURED_ESTIMATE");
        String payload = codec.write(request);
        String requestHash = codec.hash(Map.of("storyboardId", boardId, "briefId", context.briefId(),
                "scope", input.scope(), "regenerate", input.regenerateAll(),
                "expectedWorkspaceRevision", input.expectedWorkspaceRevision(),
                "maxAdditionalCostCny", input.maxAdditionalCostCny().stripTrailingZeros(),
                "scenes", fingerprint));
        var rows = new ArrayList<CreationVideo>();
        int position = 0;
        for (ScenePlan scene : estimate.scenes()) {
            var row = new CreationVideo();
            row.setSceneId(scene.sceneId()); row.setTaskId(task); row.setOwnerId(owner);
            row.setBriefId(context.briefId()); row.setStoryboardId(boardId); row.setPosition(position++);
            row.setPromptText(scene.prompt()); row.setPromptSha256(scene.promptSha256());
            row.setSceneDurationMs(scene.durationMs());
            row.setRequestedSeconds(scene.requestedSeconds() == null ? 0 : scene.requestedSeconds());
            row.setStatus("GENERATE".equals(scene.action()) ? "QUEUED" : "READY");
            row.setVideoAssetId(scene.reuseAssetId());
            row.setVideoDurationMs(scene.reuseAssetId() == null ? null : scene.durationMs());
            rows.add(row);
        }
        var started = videos.start(task, owner, key, "video-" + digest(key), boardId, context.briefId(),
                input.scope(), requestHash, payload, input.maxAdditionalCostCny(), input.expectedWorkspaceRevision(),
                rows);
        return view(task, owner, started.runId());
    }

    public Preparation view(String task, String owner, String runId) {
        requireTask(task, owner);
        var job = videos.job(task, owner, runId);
        RunView run = runtime.getRun(task, owner, runId);
        var scenes = new ArrayList<SceneView>();
        var proposals = new ArrayList<Proposal>();
        for (CreationVideo row : videos.scenes(runId)) {
            List<Proposal> own = proposals(row.getProposalJson());
            proposals.addAll(own);
            scenes.add(new SceneView(row.getSceneId(), row.getPosition(), row.getStatus(), row.getPromptText(),
                    row.getPromptSha256(), row.getSceneDurationMs(), row.getRequestedSeconds(), row.getVideoAssetId(),
                    row.getVideoDurationMs(), row.getVideoWidth(), row.getVideoHeight(), row.getProviderTaskId(),
                    row.getErrorCode(), List.copyOf(own)));
        }
        return new Preparation(runId, run.status(), run.stage(), job.storyboardId(), job.storyboardResultId(),
                !proposals.isEmpty(), List.copyOf(scenes), List.copyOf(proposals));
    }

    // ---------------------------------------------------------------- 计划

    private record Context(String briefId, String dialogueMode, Candidate candidate, List<Scene> scenes,
                           List<Asset> frozen, Map<String, Asset> assetMap) { }

    private record Plan(Context context, Estimate estimate) { }

    private Plan plan(String task, String owner, String boardId, String scope, boolean regenerate, List<String> sceneIds) {
        requireTask(task, owner);
        if (!"SELECTED".equals(scope) && !"ALL".equals(scope)) throw conflict("视频生成范围只能是 SELECTED 或 ALL");
        var board = boards.board(task, owner, boardId).orElseThrow(() -> conflict("故事板不存在或不属于本任务"));
        var brief = persistence.brief(task, owner, board.briefId());
        JsonNode briefDocument = codec.read(brief.documentJson());
        Candidate candidate = codec.candidate(codec.read(board.documentJson()).path("candidate"));
        List<Asset> frozen = persistence.frozen(board);
        Map<String, Asset> assets = new HashMap<>();
        for (Asset asset : frozen) assets.put(asset.assetId(), asset);
        var capability = capability();
        var blockers = new ArrayList<String>(capability.blockers());
        List<Scene> targets = com.link.linkagent.creator.creation.storyboard.service.StoryboardSelection.select(candidate.scenes(), sceneIds).stream()
                .filter(scene -> "ALL".equals(scope) || "AI_VIDEO".equals(scene.layout())).toList();
        if (sceneIds != null && targets.size() != sceneIds.size())
            throw conflict("按场景生成只能选择当前范围内的 AI 视频场景");
        if (targets.isEmpty())
            blockers.add("本故事板没有 AI 视频场景；如需全片切换请使用 scope=ALL 并确认画面来源变更");
        if (targets.size() > settings.getMaxScenes())
            blockers.add("本次视频生成镜头超过单次受理上限 " + settings.getMaxScenes() + " 个，请分次生成");
        String mode = briefDocument.path("dialogueMode").asText();
        // 原声简报的画面与声音都来自用户视频；AI 片段没有可保留的原声轨，整单不能在付费后才失败。
        if ("SOURCE".equals(mode)) blockers.add("本简报要求保留原声，AI 画面无法提供可保留的原声轨；请改用配音或无对白简报后再生成");
        var plans = new ArrayList<ScenePlan>();
        int generate = 0, reuse = 0, switches = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Scene scene : targets) {
            boolean switchToAi = !"AI_VIDEO".equals(scene.layout());
            if (switchToAi) switches++;
            String prompt = scene.effectiveVisualPrompt();
            String sha = digest(prompt);
            long quantized = quantizedSceneMs(scene.durationMs());
            var own = new ArrayList<String>();
            if (prompt.isBlank()) own.add("场景 " + scene.id() + " 缺少可用的画面描述");
            if (prompt.codePointCount(0, prompt.length()) > settings.getMaxPromptCharacters())
                own.add("场景 " + scene.id() + " 的画面描述超过 " + settings.getMaxPromptCharacters() + " 字符");
            // 模型是否输出音轨无法预先承诺；原声镜头必须由作者显式改用配音或无对白，不能默认替换。
            if ("ORIGINAL".equals(scene.audioMode()))
                own.add("场景 " + scene.id() + " 当前保留原声，AI 片段不承诺模型输出音轨；请改用配音或无对白后再生成");
            String reused = regenerate ? null : reusable(task, owner, scene, assets, sha, quantized);
            Integer seconds = null;
            BigDecimal usd = BigDecimal.ZERO, cny = BigDecimal.ZERO;
            if (reused == null) {
                seconds = smallestDuration(quantized);
                if (seconds == null)
                    own.add("场景 " + scene.id() + " 需要 " + ((quantized + 999) / 1000) + " 秒，超过单次生成上限 "
                            + maxSupportedSeconds() + " 秒；请拆分或缩短场景后重新确认");
                else {
                    usd = settings.getUsdPerSecond().multiply(BigDecimal.valueOf(seconds));
                    cny = toCny(usd, capability.usdToCny());
                    total = total.add(cny);
                    generate++;
                }
            } else reuse++;
            String action = !own.isEmpty() ? "BLOCKED" : reused == null ? "GENERATE" : "REUSE";
            if (!own.isEmpty() && reused != null) {
                // 有可复用片段时也保留阻断原因，避免用旧片段掩盖声音或提示词问题。
                action = "BLOCKED";
            }
            boolean clearsBody = switchToAi && scene.body() != null && !scene.body().isEmpty();
            plans.add(new ScenePlan(scene.id(), scene.layout(), action, switchToAi, clearsBody, scene.durationMs(),
                    seconds, prompt, sha, reused, usd, cny, List.copyOf(own)));
            blockers.addAll(own);
        }
        var estimate = new Estimate(boardId, brief.briefId(), scope, capability.configured(), regenerate,
                capability.provider(), capability.model(), capability.durationsSeconds(), capability.width(),
                capability.height(), capability.usdPerSecond(), capability.usdToCny(), capability.priceBasis(),
                plans.size(), generate, reuse, switches, total, List.copyOf(plans), List.copyOf(blockers));
        return new Plan(new Context(brief.briefId(), mode, candidate, targets, frozen, assets), estimate);
    }

    /** 片段复用只看提示词摘要、素材可用性与实际时长，不跨任务猜测或重新编码。 */
    private String reusable(String task, String owner, Scene scene, Map<String, Asset> assets, String sha, long quantized) {
        if (scene.visualAssetId() != null) {
            Asset asset = assets.get(scene.visualAssetId());
            if (asset != null && "VIDEO_CLIP".equals(asset.kind()) && asset.metadata() != null
                    && "AI_VIDEO".equals(asset.metadata().path("generated").path("kind").asText())
                    && sha.equals(asset.metadata().path("generated").path("promptSha256").asText())
                    && assetDurationMs(asset) >= quantized - 1)
                return asset.assetId();
        }
        return videos.reusable(task, owner, sha, quantized).map(CreationVideo::getVideoAssetId).orElse(null);
    }

    private Integer smallestDuration(long requiredMs) {
        return settings.durations().stream().filter(seconds -> seconds * 1000L >= requiredMs)
                .min(Integer::compareTo).orElse(null);
    }

    private int maxSupportedSeconds() {
        return settings.durations().stream().max(Integer::compareTo).orElse(0);
    }

    private List<String> configurationBlockers(VideoGenerationProvider provider) {
        var blockers = new ArrayList<String>();
        if (!capabilities.videoEnabled()) blockers.add(capabilities.disabledReason()==null?"AI 视频生成未启用：已在设置中关闭":capabilities.disabledReason());
        if (settings.getBaseUrl() == null || settings.getBaseUrl().isBlank())
            blockers.add("未配置兼容协议的视频生成服务地址");
        if (settings.getApiKey() == null || settings.getApiKey().isBlank())
            blockers.add("未配置视频生成密钥，密钥只保存在后端");
        if (settings.getModel() == null || settings.getModel().isBlank()) blockers.add("未配置视频生成模型");
        if (settings.durations().isEmpty()) blockers.add("未配置供应商支持的生成时长");
        if (provider == null) blockers.add("视频生成供应商适配尚未加载");
        return blockers;
    }

    private VideoGenerationProvider provider() { return providers.isEmpty() ? null : providers.getFirst(); }

    private BigDecimal toCny(BigDecimal usd, BigDecimal rate) {
        BigDecimal cny = usd.multiply(rate).setScale(6, RoundingMode.CEILING);
        return cny.signum() == 0 ? new BigDecimal("0.000001") : cny;
    }

    private List<Proposal> proposals(String value) {
        if (value == null) return List.of();
        try { return json.readValue(value, new TypeReference<List<Proposal>>() { }); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return List.of(); }
    }

    private static long quantizedSceneMs(long millis) {
        return Math.round(Math.round(millis * 30.0 / 1000) * 1000.0 / 30);
    }

    static double assetDurationMs(Asset asset) {
        if (asset == null || asset.metadata() == null) return 0;
        JsonNode streams = asset.metadata().path("normalized").path("streams");
        for (JsonNode stream : streams)
            if ("video".equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt() != 1) {
                try { return Double.parseDouble(stream.path("duration").asText()) * 1000; }
                catch (RuntimeException exception) { return 0; }
            }
        return 0;
    }

    private void requireTask(String task, String owner) {
        if (media.countTaskByOwner(task, owner) != 1) throw conflict("创作任务不存在");
    }
}
