package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
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
    public StoryboardPersistence(StoryboardMapper mapper,MediaUploadMapper media,CreatorTaskMapper tasks,CreationRuntimeMapper runs,
            CreationWorkspaceMapper workspace,CreationRuntimeService runtime,CreationAssetService assets,StoryboardCodec codec,
            ObjectMapper json,CreationBillingProperties prices,StoryboardTextGenerator generator){
        this.mapper=mapper;this.media=media;this.tasks=tasks;this.runs=runs;this.workspace=workspace;this.runtime=runtime;
        this.assets=assets;this.codec=codec;this.json=json;this.prices=prices;this.generator=generator;
    }
    public Brief saveBrief(String task,String owner,String key,BriefInput input) {
        codec.valid(input);lock(task,owner);String hash=codec.hash(input);var old=mapper.briefByKey(task,owner,key);
        if(old.isPresent()){same(hash,old.get().requestHash());return old.get();}
        idle(task,owner,input.expectedWorkspaceRevision());snapshotAssets(task,owner,input.assetIds());
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
        codec.valid(input);lock(task,owner);String hash=codec.hash(input);var old=mapper.boardByKey(task,owner,key);
        if(old.isPresent()){same(hash,old.get().requestHash());return old.get();}
        idle(task,owner,input.expectedWorkspaceRevision());var brief=brief(task,owner,input.briefId());
        if(mapper.briefRevision(task,owner)!=brief.revision())throw conflict("当前简报已更新，请明确使用新简报保存工程");
        if(input.parentStoryboardId()!=null)mapper.board(task,owner,input.parentStoryboardId()).orElseThrow(()->conflict("父故事板不属于本任务"));
        var board=saveBoard(brief,input.candidate(),snapshotAssets(task,owner,ids(brief)),key,hash,input.parentStoryboardId(),null,false);
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
        BigDecimal reservation=prices.getTextInputUsdPerMillionTokens().multiply(BigDecimal.valueOf(inputUpper))
                .add(prices.getTextOutputUsdPerMillionTokens().multiply(BigDecimal.valueOf(4096)))
                .multiply(prices.getUsdToCny()).divide(new BigDecimal("1000000"),6,RoundingMode.CEILING)
                .max(new BigDecimal("0.000001"));
        request.putObject("price").put("model",generator.modelName()).put("usdToCny",prices.getUsdToCny()).put("inputUsdPerMillion",prices.getTextInputUsdPerMillionTokens()).put("outputUsdPerMillion",prices.getTextOutputUsdPerMillionTokens()).put("maxOutputTokens",4096).put("inputTokenUpperBound",inputUpper).put("reservationCny",reservation).put("basis","CONFIGURED_ESTIMATE");
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
    private Board saveBoard(Brief brief,Candidate candidate,List<Asset> frozen,String key,String hash,String parent,String run,boolean generated) {
        codec.policy(brief,candidate,generated);String boardId=id();var doc=json.createObjectNode().put("schemaVersion","storyboard.v1").put("storyboardId",boardId).put("briefId",brief.briefId()).put("briefRevision",brief.revision()).put("templateVersion","controlled.v1").put("subtitleTimingSource",generated?"NOT_PROVIDED":"USER_PROVIDED");doc.set("candidate",json.valueToTree(candidate));
        var board=new Board(boardId,brief.taskId(),brief.ownerId(),next(mapper.boardRevision(brief.taskId(),brief.ownerId())),brief.briefId(),parent,run,key,hash,codec.hash(doc),codec.write(doc),codec.write(frozen),null);
        mapper.insertBoard(board);for(var a:frozen)mapper.reference(boardId,a.assetId(),brief.taskId(),brief.ownerId());return mapper.board(brief.taskId(),brief.ownerId(),boardId).orElseThrow();
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
