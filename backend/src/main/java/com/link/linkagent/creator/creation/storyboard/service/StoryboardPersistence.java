package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 任务行锁统一串行化版本、素材引用及准备受理，长模型调用留在事务外。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@Transactional
public class StoryboardPersistence {
    private final StoryboardMapper mapper;private final MediaUploadMapper media;private final CreatorTaskMapper tasks;
    private final CreationRuntimeMapper runs;private final CreationWorkspaceMapper workspace;private final CreationRuntimeService runtime;
    private final CreationAssetService assets;private final StoryboardCodec codec;private final ObjectMapper json;
    private final CreationBillingProperties prices;private final StoryboardTextGenerator generator;
    private final BillingExchangeRate rates;
    /** 测试和旧调用入口：汇率取部署配置值。 */
    public StoryboardPersistence(StoryboardMapper mapper,MediaUploadMapper media,CreatorTaskMapper tasks,CreationRuntimeMapper runs,
            CreationWorkspaceMapper workspace,CreationRuntimeService runtime,CreationAssetService assets,StoryboardCodec codec,
            ObjectMapper json,CreationBillingProperties prices,StoryboardTextGenerator generator){
        this(mapper,media,tasks,runs,workspace,runtime,assets,codec,json,prices,generator,
                BillingExchangeRate.fixed(prices.getUsdToCny()));
    }
    @Autowired
    public StoryboardPersistence(StoryboardMapper mapper,MediaUploadMapper media,CreatorTaskMapper tasks,CreationRuntimeMapper runs,
            CreationWorkspaceMapper workspace,CreationRuntimeService runtime,CreationAssetService assets,StoryboardCodec codec,
            ObjectMapper json,CreationBillingProperties prices,StoryboardTextGenerator generator,BillingExchangeRate rates){
        this.mapper=mapper;this.media=media;this.tasks=tasks;this.runs=runs;this.workspace=workspace;this.runtime=runtime;
        this.assets=assets;this.codec=codec;this.json=json;this.prices=prices;this.generator=generator;this.rates=rates;
    }
    public Brief saveBrief(String task,String owner,String key,BriefInput input) {
        codec.valid(input);lock(task,owner);String hash=codec.hash(input);var old=mapper.briefByKey(task,owner,key);
        if(old.isPresent()){same(hash,old.get().requestHash());return old.get();}
        idle(task,owner,input.expectedWorkspaceRevision());var selected=snapshotAssets(task,owner,input.assetIds());
        if(input.backgroundMusic()!=null){
            var music=input.backgroundMusic();
            if(selected.stream().noneMatch(a->a.assetId().equals(music.assetId()) && "MUSIC".equals(a.kind())))
                throw conflict("背景音乐须选择本简报内已准备完成的音乐素材");
            if(music.sourceOutMs()<=music.sourceInMs() || (long)music.fadeInMs()+music.fadeOutMs()>music.sourceOutMs()-music.sourceInMs())
                throw conflict("背景音乐截取范围或淡入淡出时长无效");
        }
        if(input.ambience()!=null){
            var ambience=input.ambience();
            if(selected.stream().noneMatch(a->a.assetId().equals(ambience.assetId()) && "MUSIC".equals(a.kind())))
                throw conflict("环境声须选择本简报内已准备完成的长音频素材");
            if(ambience.sourceOutMs()<=ambience.sourceInMs()
                    || (long)ambience.fadeInMs()+ambience.fadeOutMs()>ambience.sourceOutMs()-ambience.sourceInMs())
                throw conflict("环境声截取范围或淡入淡出时长无效");
            if(input.backgroundMusic()!=null && input.backgroundMusic().assetId().equals(ambience.assetId()))
                throw conflict("环境声与背景音乐不能引用同一素材，请分别选择");
        }
        var document=json.valueToTree(input);((com.fasterxml.jackson.databind.node.ObjectNode)document).remove("expectedWorkspaceRevision");
        var source=json.createObjectNode();var materials=source.putArray("materials");
        for(var m:tasks.listMaterialsByTaskId(task)) if(m.getContent()!=null && !m.getContent().isBlank())
            materials.addObject().put("sourceId","material:"+m.getId()).put("kind",m.getMaterialType()).put("content",m.getContent()).put("sha256",StoryboardCodec.digest(m.getContent()));
        source.set("publishing",mapper.publishing(task,owner).map(codec::read).orElse(json.nullNode()));
        if(codec.write(source).getBytes(StandardCharsets.UTF_8).length>48*1024)throw conflict("输入材料过长，请先整理本条视频要使用的文稿");
        var brief=new Brief(id(),task,owner,next(mapper.briefRevision(task,owner)),key,hash,codec.write(document),codec.write(source),null);
        mapper.insertBrief(brief);bump(task,owner,input.expectedWorkspaceRevision());return mapper.brief(task,owner,brief.briefId()).orElseThrow();
    }
    public Board save(String task,String owner,String key,Save input) {
        return saveWithRequestHash(task,owner,key,input,codec.hash(input));
    }
    /** 字幕原文与转换后 cue 各有身份，保留原请求摘要才能拒绝同键换文件。 */
    public Board saveWithRequestHash(String task,String owner,String key,Save input,String hash) {
        codec.valid(input);
        if(hash==null || !hash.matches("[0-9a-f]{64}"))throw conflict("故事板请求摘要无效");
        lock(task,owner);var old=mapper.boardByKey(task,owner,key);
        if(old.isPresent()){same(hash,old.get().requestHash());return old.get();}
        idle(task,owner,input.expectedWorkspaceRevision());var brief=brief(task,owner,input.briefId());
        if(mapper.briefRevision(task,owner)!=brief.revision())throw conflict("当前简报已更新，请明确使用新简报保存工程");
        var frozen=new ArrayList<>(snapshotAssets(task,owner,ids(brief)));
        if(input.parentStoryboardId()!=null) {
            var parent=mapper.board(task,owner,input.parentStoryboardId()).orElseThrow(()->conflict("父故事板不属于本任务"));
            // 场景编辑复用本任务已经生成的真实素材，改词或改提示词后的失效仍由编译器核对。
            for(var asset:frozen(parent)) if(asset.metadata()!=null
                    && !asset.metadata().path("generated").path("kind").asText("").isBlank()
                    && input.candidate().scenes().stream().anyMatch(s->asset.assetId().equals(s.visualAssetId()) || asset.assetId().equals(s.narrationAssetId()))
                    && frozen.stream().noneMatch(a->a.assetId().equals(asset.assetId()))) frozen.add(asset);
        }
        var board=saveBoard(brief,input.candidate(),frozen,key,hash,input.parentStoryboardId(),null,false);
        bump(task,owner,input.expectedWorkspaceRevision());return board;
    }
    public Job generate(String task,String owner,String key,Generate input) {
        codec.valid(input);lock(task,owner);String hash=codec.hash(input);String runKey="storyboard-"+StoryboardCodec.digest(key);
        var old=runs.runByKey(task,owner,runKey);
        if(old.isPresent()){var job=mapper.job(task,owner,old.get().runId()).orElseThrow(()->conflict("此幂等键属于其他运行"));same(hash,job.requestHash());return job;}
        idle(task,owner,input.expectedWorkspaceRevision());var brief=brief(task,owner,input.briefId());
        if(brief.revision()!=mapper.briefRevision(task,owner))throw conflict("简报已更新，请重新确认准备范围");
        var request=preparationRequest(brief,snapshotAssets(task,owner,ids(brief)));
        if(input.maxAdditionalCostCny().compareTo(request.path("price").path("reservationCny").decimalValue())<0)
            throw conflict("本次授权低于准备费用估算，请先查询估算并确认限额");
        String payload=codec.write(request);
        var run=runtime.startPreparation(task,owner,"PREPARE",runKey,input.expectedWorkspaceRevision(),payload,input.maxAdditionalCostCny());
        var job=new Job(run.runId(),task,owner,brief.briefId(),hash,null);mapper.insertJob(job);return job;
    }
    public Map<String,Object> estimate(String task,String owner,EstimateInput input) {
        codec.valid(input);lock(task,owner);idle(task,owner,input.expectedWorkspaceRevision());
        var brief=brief(task,owner,input.briefId());
        if(brief.revision()!=mapper.briefRevision(task,owner))throw conflict("简报已更新，请刷新后重新估算");
        var price=preparationRequest(brief,snapshotAssets(task,owner,ids(brief))).path("price");
        return Map.of("workspaceRevision",input.expectedWorkspaceRevision(),"model",price.path("model").asText(),
                "estimatedMaxAdditionalCostCny",price.path("reservationCny").decimalValue(),
                "inputTokenUpperBound",price.path("inputTokenUpperBound").asInt(),"maxOutputTokens",4096,
                "basis","CONFIGURED_ESTIMATE");
    }
    private ObjectNode preparationRequest(Brief brief,List<Asset> frozen) {
        prices.validate();
        var request=json.createObjectNode();request.put("purpose","STORYBOARD").put("briefId",brief.briefId());request.set("assets",json.valueToTree(frozen));
        var context=request.putObject("context");context.set("brief",codec.read(brief.documentJson()));context.set("sources",codec.read(brief.sourceJson()));
        var catalog=context.putArray("assets");for(var a:frozen){var entry=catalog.addObject().put("assetId",a.assetId()).put("kind",a.kind());entry.set("metadata",a.metadata());}
        // 使用同一份实际提示词与上下文估算，保留消息封装余量；配置价格不是供应商实付账单。
        int inputUpper=StoryboardWorker.prompt().getBytes(StandardCharsets.UTF_8).length
                +codec.write(context).getBytes(StandardCharsets.UTF_8).length+4096;
        BigDecimal rate=rates.current().usdToCny();
        BigDecimal reservation=prices.getTextInputUsdPerMillionTokens().multiply(BigDecimal.valueOf(inputUpper))
                .add(prices.getTextOutputUsdPerMillionTokens().multiply(BigDecimal.valueOf(4096)))
                .multiply(rate).divide(new BigDecimal("1000000"),6,RoundingMode.CEILING)
                .max(new BigDecimal("0.000001"));
        request.putObject("price").put("model",generator.modelName()).put("usdToCny",rate).put("inputUsdPerMillion",prices.getTextInputUsdPerMillionTokens()).put("outputUsdPerMillion",prices.getTextOutputUsdPerMillionTokens()).put("maxOutputTokens",4096).put("inputTokenUpperBound",inputUpper).put("reservationCny",reservation).put("basis","CONFIGURED_ESTIMATE");
        String payload=codec.write(request);if(payload.getBytes(StandardCharsets.UTF_8).length>60*1024)throw conflict("本次材料与素材元数据过多，请缩小准备范围");
        return request;
    }
    public Board finish(Job job,Run lease,Candidate candidate,List<Asset> frozen) {
        lock(job.taskId(),job.ownerId());var current=mapper.job(job.taskId(),job.ownerId(),job.runId()).orElseThrow();
        if(current.storyboardId()!=null)return mapper.board(job.taskId(),job.ownerId(),current.storyboardId()).orElseThrow();
        if(runs.liveLease(job.taskId(),job.ownerId(),job.runId(),lease.leaseOwner(),lease.leaseGeneration(),"RUNNING")!=1
                || !job.runId().equals(runs.workspace(job.taskId(),job.ownerId()).orElseThrow().activeRunId()))throw conflict("准备运行已取消或执行权失效");
        var brief=brief(job.taskId(),job.ownerId(),job.briefId());
        if(brief.revision()!=mapper.briefRevision(job.taskId(),job.ownerId()))throw conflict("准备依据已变化");
        var board=saveBoard(brief,candidate,frozen,"run-"+job.runId(),job.requestHash(),null,job.runId(),true);
        if(mapper.result(job.runId(),board.storyboardId())!=1)throw conflict("本运行已保存其他结果");
        runtime.complete(job.taskId(),job.ownerId(),job.runId(),lease.leaseOwner(),lease.leaseGeneration(),codec.write(Map.of("storyboardId",board.storyboardId(),"documentHash",board.documentHash())));
        return board;
    }
    /**
     * 配音运行保存字幕对齐后的新版本：只接受本运行实际生成的配音素材，失败时不覆盖父版本。
     * 与准备运行一样，写入前再次核对活动运行和活租约，避免取消后的迟到结果生成新版本。
     */
    public Board saveVoice(String task,String owner,String key,String runId,String worker,long generation,
            String briefId,String parentId,Candidate candidate,List<Asset> extra,Set<String> extraIds) {
        return saveGenerated(task,owner,key,runId,worker,generation,briefId,parentId,candidate,extra,extraIds,
                "ASR_ALIGNED","配音");
    }
    /** AI 视频运行保存新版本：只接受本运行实际生成并核验过的片段，旧版本与用户素材保持不变。 */
    public Board saveVideo(String task,String owner,String key,String runId,String worker,long generation,
            String briefId,String parentId,Candidate candidate,List<Asset> extra,Set<String> extraIds) {
        // 字幕来源沿用父版本，不能把 ASR 对齐的结果改写成用户提供。
        return saveGenerated(task,owner,key,runId,worker,generation,briefId,parentId,candidate,extra,extraIds,
                null,"AI 视频");
    }
    private Board saveGenerated(String task,String owner,String key,String runId,String worker,long generation,
            String briefId,String parentId,Candidate candidate,List<Asset> extra,Set<String> extraIds,
            String timingSource,String label) {
        lock(task,owner);
        var replay=mapper.boardByKey(task,owner,key);
        if(replay.isPresent())return replay.get();
        var run=runs.run(task,owner,runId).orElseThrow(()->conflict(label+"运行不存在"));
        if(!"PREPARE".equals(run.kind()) || !runId.equals(runs.workspace(task,owner).orElseThrow().activeRunId())
                || runs.liveLease(task,owner,runId,worker,generation,"RUNNING")!=1)
            throw conflict(label+"运行已取消、执行权失效或已被接管");
        var brief=brief(task,owner,briefId);
        if(brief.revision()!=mapper.briefRevision(task,owner))throw conflict(label+"依据已变化，请重新生成");
        Board parent=parentId==null?null:mapper.board(task,owner,parentId).orElseThrow(()->conflict("父故事板不属于本任务"));
        // 新版本必须继承父版本实际引用的素材：配音片段、AI 片段或用户素材都不能因为一次局部生成而丢失。
        var frozen=new ArrayList<>(snapshotAssets(task,owner,ids(brief)));
        if(parent!=null) for(Asset asset:frozen(parent)) if(frozen.stream().noneMatch(a->a.assetId().equals(asset.assetId()))) frozen.add(asset);
        for(Asset asset:extra) if(frozen.stream().noneMatch(a->a.assetId().equals(asset.assetId()))) frozen.add(asset);
        String source=timingSource==null?parentTimingSource(parent):timingSource;
        String hash=codec.hash(Map.of("runId",runId,"candidate",candidate,"parent",Objects.toString(parentId,"")));
        return saveBoard(brief,candidate,List.copyOf(frozen),key,hash,parentId,runId,false,source,extraIds);
    }
    private String parentTimingSource(Board parent) {
        if(parent==null)return "USER_PROVIDED";
        String value=codec.read(parent.documentJson()).path("subtitleTimingSource").asText("");
        return Set.of("USER_PROVIDED","ASR_ALIGNED","NOT_PROVIDED").contains(value)?value:"USER_PROVIDED";
    }
    private Board saveBoard(Brief brief,Candidate candidate,List<Asset> frozen,String key,String hash,String parent,String run,boolean generated) {
        return saveBoard(brief,candidate,frozen,key,hash,parent,run,generated,generated?"NOT_PROVIDED":"USER_PROVIDED",Set.of());
    }
    private Board saveBoard(Brief brief,Candidate candidate,List<Asset> frozen,String key,String hash,String parent,String run,
            boolean generated,String timingSource,Set<String> generatedVoiceAssets) {
        // 生成素材不再由调用方逐项声明：凡在冻结快照中带系统生成标记的素材都属本任务已验收产出，
        // 用户上传没有 generated 元数据，不能借这个入口把自己上传的文件伪装成生成结果。
        Set<String> generatedIds=new HashSet<>(generatedVoiceAssets);
        for(Asset asset:frozen) if(asset.metadata()!=null
                && !asset.metadata().path("generated").path("kind").asText("").isBlank()) generatedIds.add(asset.assetId());
        codec.policy(brief,candidate,generated,generatedIds);String boardId=id();var doc=json.createObjectNode().put("schemaVersion","storyboard.v1").put("storyboardId",boardId).put("briefId",brief.briefId()).put("briefRevision",brief.revision()).put("templateVersion","controlled.v1").put("subtitleTimingSource",timingSource);doc.set("candidate",json.valueToTree(candidate));
        var board=new Board(boardId,brief.taskId(),brief.ownerId(),next(mapper.boardRevision(brief.taskId(),brief.ownerId())),brief.briefId(),parent,run,key,hash,codec.hash(doc),codec.write(doc),codec.write(frozen),null);
        mapper.insertBoard(board);for(var a:frozen)mapper.reference(boardId,a.assetId(),brief.taskId(),brief.ownerId());return mapper.board(brief.taskId(),brief.ownerId(),boardId).orElseThrow();
    }
    /**
     * 采纳时间线调整必须同时留下新的简报版本：目标时长属于用户意图，旧简报不能就地改写。
     * 新故事板显式挂回父版本，历史工程、配音素材引用和旧简报原文都保留。
     */
    public Board saveTimeline(String task,String owner,String key,String requestHash,String boardId,
            long expectedRevision,Candidate candidate,ObjectNode document,String timingSource,
            Set<String> generatedVoiceAssets,List<Asset> frozen) {
        return saveRevision(task,owner,key,requestHash,boardId,expectedRevision,candidate,document,timingSource,generatedVoiceAssets,frozen,null);
    }
    public Board saveRevision(String task,String owner,String key,String requestHash,String boardId,
            long expectedRevision,Candidate candidate,ObjectNode document,String timingSource,
            Set<String> generatedVoiceAssets,List<Asset> frozen,String revisedSourceJson) {
        if(candidate==null || document==null)throw conflict("时间线调整缺少场景或简报内容");
        lock(task,owner);
        var replay=mapper.boardByKey(task,owner,key);
        if(replay.isPresent()){same(requestHash,replay.get().requestHash());return replay.get();}
        idle(task,owner,expectedRevision);
        var parent=mapper.board(task,owner,boardId).orElseThrow(()->conflict("故事板不存在或不属于本任务"));
        var current=brief(task,owner,parent.briefId());
        if(current.revision()!=mapper.briefRevision(task,owner))throw conflict("当前简报已更新，请使用最新简报的故事板调整时间线");
        long target=document.path("targetDurationMs").asLong();
        if(target<1000 || target>180000)throw conflict("调整后的总时长必须在 1 至 180 秒之间");
        long frames=0;
        for(var scene:candidate.scenes())frames+=Math.round(scene.durationMs()*30.0/1000);
        if(Math.round(frames*1000.0/30)!=target)throw conflict("新简报目标时长与调整后场景帧长不一致");
        String briefKey=key+"-brief";
        if(briefKey.length()>128)throw conflict("时间线幂等键过长");
        var nextBrief=new Brief(id(),task,owner,next(mapper.briefRevision(task,owner)),briefKey,requestHash,
                codec.write(document),revisedSourceJson==null?current.sourceJson():revisedSourceJson,null);
        mapper.insertBrief(nextBrief);
        var saved=saveBoard(nextBrief,candidate,frozen,key,requestHash,boardId,null,false,timingSource,generatedVoiceAssets);
        bump(task,owner,expectedRevision);
        return saved;
    }
    public List<Asset> snapshotAssets(String task,String owner,List<String> ids) {
        if(new HashSet<>(ids).size()!=ids.size())throw conflict("素材选择不能重复");var result=new ArrayList<Asset>();
        for(String id:ids){var a=assets.require(task,owner,id);if(!"READY".equals(a.getStatus()))throw conflict("只可引用本任务已准备完成的素材");var file=assets.objects(a).stream().filter(f->"NORMALIZED".equals(f.role())).findFirst().orElseThrow(()->conflict("素材缺少工作文件"));result.add(new Asset(id,a.getKind(),a.getBucketName(),file.objectKey(),file.sha256(),file.bytes(),file.contentType(),codec.read(a.getMetadataJson())));}
        return List.copyOf(result);
    }
    public List<Asset> frozen(Board board) {try{return json.readValue(board.assetSnapshotJson(),new TypeReference<List<Asset>>(){});}catch(Exception ex){throw conflict("冻结素材清单无法读取");}}
    public Brief brief(String task,String owner,String id){return mapper.brief(task,owner,id).orElseThrow(()->conflict("简报不存在或不属于本任务"));}
    private List<String> ids(Brief brief){var result=new ArrayList<String>();codec.read(brief.documentJson()).path("assetIds").forEach(n->result.add(n.asText()));return result;}
    private void lock(String task,String owner){if(media.lockMediaTask(task,owner).isEmpty())throw conflict("任务不存在");media.lockTaskDraftVersions(task,owner);media.adoptMediaWorkspace(task,owner);}
    private void idle(String task,String owner,long expected){var w=runs.workspace(task,owner).orElseThrow();if(w.workspaceRevision()!=expected || w.activeRunId()!=null || workspace.countActiveWork(task,owner)>0)throw conflict("工作区已变化或仍有活动制作，请先取消并刷新");if(media.countPublishingFacts(task,owner)>0)throw conflict("已进入发布后流程，请新建修订任务");}
    private void bump(String task,String owner,long expected){if(runs.bump(task,owner,expected)!=1)throw conflict("工作区已变化");}
    private int next(int old){if(old==Integer.MAX_VALUE)throw conflict("版本序号已达到上限");return old+1;}
    private void same(String a,String b){if(!a.equals(b))throw conflict("同一幂等键对应不同请求");}
    private static String id(){return UUID.randomUUID().toString();}
}
