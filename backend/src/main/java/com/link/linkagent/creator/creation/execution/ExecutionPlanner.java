package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.aivideo.service.CreationVideoService;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;
import static com.link.linkagent.creator.creation.execution.ExecutionModels.*;

/** 确认前只读估算；缺素材和未配置必须先显示，不创建运行或发送供应商请求。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class ExecutionPlanner {
    private static final Set<String> VOICE = Set.of("TTS_AUDIO_MISSING","TTS_TEXT_MISMATCH","TTS_CUES_MISSING");
    private static final Set<String> VIDEO = Set.of("AI_VIDEO_ASSET_MISSING","AI_VIDEO_PROMPT_MISMATCH");
    private final StoryboardMapper boards;
    private final StoryboardPersistence persistence;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final CreationVoiceService voice;
    private final CreationVideoService video;
    private final CreationRuntimeService runtime;
    private final CreationBillingProperties prices;
    private final CreatorMediaProperties media;
    private final CreationCapabilityGate capabilities;
    private final BillingExchangeRate rates;
    private final FixedProjectRendererProperties renderer;
    private final CreationWorkerProperties worker;
    private final ProviderConfiguration configuration;
    private final com.link.linkagent.creator.creation.render.RendererEnvironmentCheck environment;
    private final StoryboardTextGenerator textGenerator;
    public ExecutionPlanner(StoryboardMapper boards, StoryboardPersistence persistence, StoryboardCodec codec,
            StoryboardCompiler compiler, CreationVoiceService voice, CreationVideoService video,
            CreationRuntimeService runtime, CreationBillingProperties prices, CreatorMediaProperties media,
            CreationCapabilityGate capabilities, BillingExchangeRate rates, FixedProjectRendererProperties renderer,
            CreationWorkerProperties worker) {
        this(boards,persistence,codec,compiler,voice,video,runtime,prices,media,capabilities,rates,renderer,worker,null,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionPlanner(StoryboardMapper boards,StoryboardPersistence persistence,StoryboardCodec codec,StoryboardCompiler compiler,
            CreationVoiceService voice,CreationVideoService video,CreationRuntimeService runtime,CreationBillingProperties prices,
            CreatorMediaProperties media,CreationCapabilityGate capabilities,BillingExchangeRate rates,FixedProjectRendererProperties renderer,
            CreationWorkerProperties worker,ProviderConfiguration configuration,com.link.linkagent.creator.creation.render.RendererEnvironmentCheck environment,StoryboardTextGenerator textGenerator) {
        this.boards=boards;this.persistence=persistence;this.codec=codec;this.compiler=compiler;this.voice=voice;
        this.video=video;this.runtime=runtime;this.prices=prices;this.media=media;this.capabilities=capabilities;
        this.rates=rates;this.renderer=renderer;this.worker=worker;
        this.configuration=configuration;this.environment=environment;
        this.textGenerator=textGenerator;
    }
    public String fingerprint(){return StoryboardCodec.digest((configuration==null?"test-config":configuration.fingerprint())+"\n"+
        (textGenerator==null?"":textGenerator.modelName())+"\n"+media.getPreflight().getVideoModel()+"\n"+media.getPreflight().getAsrModel()+"\n"+media.getPreflight().getSegmentReviewModel()+"\n"+
        media.getPreflight().getSegmentReviewMaxCount()+"\n"+media.getProcessing().getAsrUsdPerSecond()+"\n"+
        prices.getVideoUsdUpperBound()+"\n"+prices.getTextScreeningUsdUpperBound()+"\n"+prices.getSegmentReviewUsdUpperBound()+"\n"+
        prices.getTextInputUsdPerMillionTokens()+"\n"+prices.getTextOutputUsdPerMillionTokens());}
    public Estimate estimate(String task,String owner,String boardId,String kind) {
        if (!Set.of("SAMPLE","FULL","REVISION").contains(kind)) throw conflict("制作范围只能是样片、整片或修改版");
        var snapshot=runtime.snapshot(task,owner);
        var board=boards.board(task,owner,boardId).orElseThrow(()->conflict("故事板不存在"));
        var brief=persistence.brief(task,owner,board.briefId());
        var candidate=codec.candidate(codec.read(board.documentJson()).path("candidate"));
        var ids="SAMPLE".equals(kind)?StoryboardSelection.sample(candidate):null;
        var scope=StoryboardSelection.scope(codec.read(brief.documentJson()),candidate,ids);
        var check=compiler.check(boardId,scope.brief(),scope.candidate(),persistence.frozen(board));
        var blocked=new ArrayList<String>();
        if(configuration!=null && configuration.disabledReason()!=null)blocked.add(configuration.disabledReason());
        if(environment!=null){String problem=environment.problem();if(problem!=null)blocked.add(problem);}
        if(!snapshot.initialized()) blocked.add("请先设置本条视频的制作预算");
        if(!renderer.isEnabled() || !worker.isEnabled()) blocked.add("自动制作执行器尚未启用，请在部署配置中启用后重启");
        if(brief.revision()!=boards.briefRevision(task,owner)) blocked.add("简报已更新，请使用新简报的故事板");
        List<String> voiceIds=check.problems().stream().filter(p->VOICE.contains(p.code())).map(Problem::sceneId).distinct().toList();
        List<String> videoIds=check.problems().stream().filter(p->VIDEO.contains(p.code())).map(Problem::sceneId).distinct().toList();
        for(var problem:check.problems()) if(!VOICE.contains(problem.code()) && !VIDEO.contains(problem.code())
                && !("CLIP_DURATION_INVALID".equals(problem.code()) && videoIds.contains(problem.sceneId())))
            blocked.add(problem.message());
        BigDecimal preparation=BigDecimal.ZERO,review=BigDecimal.ZERO;
        if(!voiceIds.isEmpty()) {
            var estimate=voice.estimate(task,owner,boardId,voiceIds);
            preparation=preparation.add(estimate.estimatedMaxAdditionalCostCny());blocked.addAll(estimate.blockers());
        }
        if(!videoIds.isEmpty()) {
            var estimate=video.estimate(task,owner,boardId,"SELECTED",false,videoIds);
            preparation=preparation.add(estimate.estimatedMaxCostCny());blocked.addAll(estimate.blockers());
        }
        if(!"SAMPLE".equals(kind)) {
            var preflight=media.getPreflight();
            if(preflight.getDashScopeApiKey()==null || preflight.getDashScopeApiKey().isBlank()
                    || preflight.getVideoModel()==null || preflight.getVideoModel().isBlank()
                    || preflight.getSegmentReviewModel()==null || preflight.getSegmentReviewModel().isBlank())
                blocked.add("完整试映尚未配置密钥或视频理解模型，请先完成制作配置后再制作整片");
            var publishing=boards.publishing(task,owner).map(codec::read).orElse(null);
            if(publishing==null) blocked.add("请先在发布方案页确认本条视频的发布方向");
            else if(!publishing.equals(codec.read(brief.sourceJson()).path("publishing")))
                blocked.add("发布方向已变化，请保存新简报后再制作");
            if(!capabilities.mediaAnalysisEnabled()) blocked.add("完整试映已在设置中暂停，整片制作暂不可启动");
            prices.validate();
            // 三类假想观众在一次请求中生成，片段复核按配置中的最大数量预留。
            BigDecimal usd=prices.getVideoUsdUpperBound()
                    .add(prices.getTextScreeningUsdUpperBound())
                    .add(prices.getSegmentReviewUsdUpperBound().multiply(BigDecimal.valueOf(media.getPreflight().getSegmentReviewMaxCount())))
                    .add(media.getProcessing().getAsrUsdPerSecond().multiply(BigDecimal.valueOf((scope.durationMs()+999)/1000)));
            review=usd.multiply(rates.current().usdToCny()).setScale(6,RoundingMode.CEILING);
        }
        if(preparation.add(review).signum()>0 && snapshot.budget()!=null && snapshot.budget().paidCallsBlocked())
            blocked.add("制作预算已用尽或有待核实费用，当前不能新增付费步骤");
        return new Estimate(boardId,board.documentHash(),check.renderContentHash(),kind,snapshot.workspaceRevision(),scope.sceneIds(),scope.durationMs(),
                voiceIds,videoIds,preparation,review,preparation.add(review),"CONFIGURED_ESTIMATE",
                fingerprint(),blocked.stream().distinct().toList());
    }
}
