package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import com.link.linkagent.creator.creation.aivideo.model.VideoModels;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoService;
import com.link.linkagent.creator.creation.voice.model.VoiceModels;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceService;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 用户只确认一次范围；每次子运行受理、关系登记和检查点同事务提交。 */
@Service
@Transactional
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class ExecutionService {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","CANCELLED");
    private final ExecutionMapper executions;
    private final ExecutionPlanner planner;
    private final MediaUploadMapper media;
    private final CreationRuntimeMapper runs;
    private final CreationRuntimeService runtime;
    private final StoryboardMapper boards;
    private final StoryboardCodec codec;
    private final CreationVoiceService voice;
    private final CreationVideoService video;
    private final AutomaticProductionPlanService automatic;
    private final CreationMediaMapper links;
    private final CreationPackageMapper packages;
    public ExecutionService(ExecutionMapper executions,ExecutionPlanner planner,MediaUploadMapper media,
            CreationRuntimeMapper runs,CreationRuntimeService runtime,StoryboardMapper boards,StoryboardCodec codec,
            CreationVoiceService voice,CreationVideoService video,AutomaticProductionPlanService automatic,
            CreationMediaMapper links,CreationPackageMapper packages) {
        this.executions=executions;this.planner=planner;this.media=media;this.runs=runs;this.runtime=runtime;
        this.boards=boards;this.codec=codec;this.voice=voice;this.video=video;this.automatic=automatic;
        this.links=links;this.packages=packages;
    }
    public Estimate estimate(String task,String owner,EstimateInput input) {
        var estimate=planner.estimate(task,owner,input.storyboardId(),input.kind());
        if(estimate.workspaceRevision()!=input.expectedWorkspaceRevision()) throw conflict("工作区已变化，请刷新后重新估算");
        return estimate;
    }
    public View start(String task,String owner,String key,Start input) {
        codec.valid(input);lock(task,owner);
        runtime.checkConfiguration();
        if(key==null || key.isBlank() || key.length()>128) throw conflict("制作确认需要非空幂等键，最多 128 字符");
        String hash=codec.hash(input);
        var replay=executions.byKey(task,owner,key);
        if(replay.isPresent()) {
            if(!hash.equals(replay.get().requestHash())) throw conflict("同一幂等键对应不同制作确认");
            return view(replay.get());
        }
        var snapshot=runtime.snapshot(task,owner);
        if(snapshot.workspaceRevision()!=input.expectedWorkspaceRevision() || snapshot.activeRun()!=null)
            throw conflict("工作区已变化或仍有运行，请刷新后再确认");
        if(executions.list(task,owner).stream().anyMatch(e->!TERMINAL.contains(e.status())))
            throw conflict("本任务仍有未结束的制作，请先查看或取消");
        var estimate=estimate(task,owner,new EstimateInput(input.expectedWorkspaceRevision(),input.storyboardId(),input.kind()));
        if(!input.storyboardHash().equals(estimate.storyboardHash())) throw conflict("故事板已变化，请重新确认制作范围");
        if(!input.providerFingerprint().equals(estimate.providerFingerprint()))throw conflict("制作配置已变化，请重新估算并确认");
        if(!estimate.blockers().isEmpty()) throw conflict(estimate.blockers().getFirst());
        if("REVISION".equals(input.kind()))requireRevisionBase(task,owner,input.storyboardId());
        if(input.maxAdditionalCostCny().compareTo(estimate.estimatedMaxAdditionalCostCny())<0)
            throw conflict("本次限额低于完整制作估算，请调整范围或明确限额");
        if(snapshot.budget()==null || input.maxAdditionalCostCny().compareTo(snapshot.budget().remainingCny())>0)
            throw conflict("本次限额超过任务可用预算，请先调整预算");
        var publishing=boards.publishing(task,owner).map(codec::read).orElse(null);
        String id=UUID.randomUUID().toString();
        var execution=new Execution(id,task,owner,input.kind(),"ACTIVE","QUEUED",input.storyboardId(),input.storyboardId(),
                codec.write(estimate.sceneIds()),publishing==null?null:publishing.path("publishingSessionId").asText(),
                publishing==null?null:publishing.path("publishingResultId").asText(),
                media.findMediaWorkspace(task,owner).orElseThrow().currentVideoVersionId(),input.maxAdditionalCostCny(),null,
                snapshot.workspaceRevision()+1,key,hash,estimate.providerFingerprint(),null,"[]",null);
        if(runs.bump(task,owner,snapshot.workspaceRevision())!=1) throw conflict("工作区已变化，请重新确认");
        executions.insert(execution);
        return view(require(task,owner,id));
    }
    @Transactional(readOnly=true)
    public List<View> list(String task,String owner) {
        requireTask(task,owner);return executions.list(task,owner).stream().map(this::view).toList();
    }
    @Transactional(readOnly=true)
    public View get(String task,String owner,String id) {requireTask(task,owner);return view(require(task,owner,id));}
    public View cancel(String task,String owner,String id) {
        lock(task,owner);var e=require(task,owner,id);
        if(TERMINAL.contains(e.status()))return view(e);
        String state="CANCELLED";
        if(e.currentRunId()!=null) {
            var result=runtime.cancel(task,owner,e.currentRunId());
            if(!Set.of("COMPLETED","CANCELLED","FAILED").contains(result.status())) state="CANCEL_REQUESTED";
        }
        update(e,state,state,e.storyboardId(),e.currentRunId(),runtime.snapshot(task,owner).workspaceRevision(),null);
        return view(require(task,owner,id));
    }
    /** 返回需要事务外执行的动作；后台重启后再次从同一个子运行恢复。 */
    public record Work(Execution execution,String action) { }
    public Work advance(String task,String owner,String id) {
        lock(task,owner);var e=require(task,owner,id);
        if(TERMINAL.contains(e.status()) || "WAITING_USER".equals(e.status()))return null;
        if("CANCEL_REQUESTED".equals(e.status())) {
            if(e.currentRunId()==null || Set.of("COMPLETED","CANCELLED","FAILED").contains(runtime.getRun(task,owner,e.currentRunId()).status()))
                update(e,"CANCELLED","CANCELLED",e.storyboardId(),e.currentRunId(),runtime.snapshot(task,owner).workspaceRevision(),null);
            else if("SAMPLE".equals(e.stage()))return new Work(e,"SAMPLE");
            return null;
        }
        if(!e.providerFingerprint().equals(planner.fingerprint())) {
            if(e.currentRunId()!=null)runtime.cancel(task,owner,e.currentRunId());
            waitForUser(e,"PROVIDER_CONFIGURATION_CHANGED");return null;
        }
        if(e.currentRunId()!=null) {
            var run=runtime.getRun(task,owner,e.currentRunId());
            if(Set.of("FAILED","CANCELLED").contains(run.status())) {waitForUser(e,run.errorCode()==null?"PREPARATION_FAILED":run.errorCode());return null;}
            if(!"COMPLETED".equals(run.status())) {
                if("MEDIA_BLOCKED".equals(run.stage())) {waitForUser(e,run.errorCode());return null;}
                return new Work(e,"FULL".equals(e.stage())?"ATTACH":"SAMPLE".equals(e.stage())?"SAMPLE":"WAIT");
            }
            if("FULL".equals(e.stage())) {update(e,"COMPLETED","COMPLETED",e.storyboardId(),e.currentRunId(),runtime.snapshot(task,owner).workspaceRevision(),null);return null;}
            String board;
            if("VOICE".equals(e.stage())) {
                var result=voice.view(task,owner,e.currentRunId());
                if(result.requiresUserDecision()) {waitForUser(e,"TIMING_NEEDS_DECISION");return null;}
                board=result.storyboardId();
            } else if("VIDEO".equals(e.stage())) {
                var result=video.view(task,owner,e.currentRunId());
                if(result.requiresUserDecision()) {waitForUser(e,"TIMING_NEEDS_DECISION");return null;}
                board=result.storyboardResultId();
            } else return null;
            if(board==null) {waitForUser(e,"PREPARATION_RESULT_MISSING");return null;}
            long revision=runtime.snapshot(task,owner).workspaceRevision();
            if(revision!=e.expectedWorkspaceRevision()+1) {waitForUser(e,"WORKSPACE_CHANGED");return null;}
            update(e,"ACTIVE","QUEUED",board,null,revision,null);
            e=require(task,owner,id);
        }
        var snapshot=runtime.snapshot(task,owner);
        if(snapshot.workspaceRevision()!=e.expectedWorkspaceRevision() || snapshot.activeRun()!=null) {
            waitForUser(e,"WORKSPACE_CHANGED");return null;
        }
        var estimate=planner.estimate(task,owner,e.storyboardId(),e.kind());
        if(!estimate.sceneIds().equals(sceneIds(e))) {waitForUser(e,"WORKSPACE_CHANGED");return null;}
        if(!estimate.blockers().isEmpty()) {waitForUser(e,"MATERIALS_NEED_ATTENTION");return null;}
        var costs=executions.costs(id);
        BigDecimal remaining=e.maxAdditionalCostCny().subtract(costs.occupied());
        if(costs.unknownCny().signum()>0 || costs.overrunCount()>0) {waitForUser(e,"COST_UNKNOWN");return null;}
        if(remaining.compareTo(estimate.estimatedMaxAdditionalCostCny())<0) {waitForUser(e,"LIMIT_INSUFFICIENT");return null;}
        String stage,run;
        String key="execution-"+e.executionId();
        if(!estimate.voiceSceneIds().isEmpty()) {
            stage="VOICE";run=voice.generate(task,owner,key+"-voice",e.storyboardId(),
                    new VoiceModels.Generate(snapshot.workspaceRevision(),remaining,estimate.voiceSceneIds())).runId();
        } else if(!estimate.videoSceneIds().isEmpty()) {
            stage="VIDEO";run=video.generate(task,owner,key+"-video",e.storyboardId(),
                    new VideoModels.Generate(snapshot.workspaceRevision(),remaining,"SELECTED",false,estimate.videoSceneIds())).runId();
        } else if(!"SAMPLE".equals(e.kind())) {
            stage="FULL";
            run=automatic.start(task,owner,key+"-full",snapshot.workspaceRevision(),
                    new AutomaticProductionRequest(e.publishingSessionId(),e.publishingResultId(),e.baseVersionId(),
                            e.storyboardId(),estimate.renderContentHash(),estimate.durationMs()),remaining).runId();
        } else {
            stage="SAMPLE";
            var request=codec.read("{}").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("purpose","SAMPLE").put("executionId",id).put("storyboardId",e.storyboardId());
            run=runtime.startPreparation(task,owner,"PREPARE",key+"-sample",snapshot.workspaceRevision(),codec.write(request),remaining).runId();
        }
        executions.link(id,run,stage);
        update(e,"ACTIVE",stage,e.storyboardId(),run,runtime.snapshot(task,owner).workspaceRevision(),null);
        return new Work(require(task,owner,id),"FULL".equals(stage)?"ATTACH":"SAMPLE".equals(stage)?"SAMPLE":"WAIT");
    }
    public void attention(String task,String owner,String id,String code) {lock(task,owner);var e=require(task,owner,id);if("ACTIVE".equals(e.status()))waitForUser(e,code);}
    public void dispatchFailed(String task,String owner,String id){lock(task,owner);require(task,owner,id);executions.dispatchFailed(id);}
    private void requireRevisionBase(String task,String owner,String boardId) {
        String version=media.findMediaWorkspace(task,owner).orElseThrow().currentVideoVersionId();
        var link=version==null?null:links.byVersion(task,owner,version).orElse(null);
        if(link==null)throw conflict("局部修改制作需要本系统工程生成的真实基准成片");
        var base=runs.run(task,owner,link.runId()).orElseThrow();
        String source=codec.read(base.requestJson()).path("storyboardId").asText();
        String parent=boards.board(task,owner,boardId).orElseThrow().parentStoryboardId();
        for(int depth=0;parent!=null && depth<100;depth++) {
            if(source.equals(parent))return;
            parent=boards.board(task,owner,parent).orElseThrow().parentStoryboardId();
        }
        throw conflict("修改工程不继承当前基准成片，请选回对应版本后再确认");
    }
    public void completeSample(String task,String owner,String id,Run lease,String worker) {
        lock(task,owner);var e=require(task,owner,id);
        if(!"ACTIVE".equals(e.status()) || !Objects.equals(e.currentRunId(),lease.runId()) || !"SAMPLE".equals(e.stage()))
            throw conflict("样片已取消或执行权已变化");
        var names=executions.generationOutputs(id,lease.leaseGeneration()).stream().map(Output::fileName).collect(java.util.stream.Collectors.toSet());
        if(!names.equals(Set.of("video.mp4","subtitles.srt","cover.png","frame-start.png","frame-middle.png","frame-end.png","render.log","manifest.json")))
            throw conflict("样片缺少本次尝试的完整产物清单，不能标为完成");
        runtime.complete(task,owner,lease.runId(),worker,lease.leaseGeneration(),codec.write(Map.of("executionId",id,"sample",true,"published",false)));
        executions.adopt(id,lease.leaseGeneration());
        update(e,"COMPLETED","COMPLETED",e.storyboardId(),lease.runId(),runtime.snapshot(task,owner).workspaceRevision(),null);
    }
    public Execution require(String task,String owner,String id) {requireTask(task,owner);return executions.find(task,owner,id).orElseThrow(()->conflict("制作记录不存在或不属于本任务"));}
    public List<String> sceneIds(Execution e) {var ids=new ArrayList<String>();codec.read(e.sceneIdsJson()).forEach(n->ids.add(n.asText()));return List.copyOf(ids);}
    private void waitForUser(Execution e,String code) {update(e,"WAITING_USER",e.stage(),e.storyboardId(),e.currentRunId(),e.expectedWorkspaceRevision(),code);}
    private void update(Execution e,String status,String stage,String board,String run,long revision,String error) {
        if(executions.update(e.executionId(),status,stage,board,run,revision,error)!=1)throw conflict("制作状态已变化，请刷新");
    }
    private View view(Execution e) {
        var run=e.currentRunId()==null?null:runtime.getRun(e.taskId(),e.ownerId(),e.currentRunId());
        var link=e.currentRunId()==null?null:links.find(e.currentRunId()).orElse(null);
        var pkg=e.currentRunId()==null?null:packages.find(e.currentRunId()).orElse(null);
        boolean preview="SAMPLE".equals(e.kind())?!executions.outputs(e.executionId()).isEmpty():link!=null;
        return new View(e.executionId(),e.kind(),e.status(),e.stage(),e.storyboardId(),sceneIds(e),e.maxAdditionalCostCny(),
                executions.costs(e.executionId()),run,link==null?null:link.versionId(),preview,
                pkg!=null && "READY".equals(pkg.status()) && link!=null && link.versionId().equals(pkg.versionId()),e.errorCode(),
                message(e.errorCode()),e.createTime());
    }
    public static String message(String code) {
        if(code==null)return null;
        return switch(code) {
            case "WORKSPACE_CHANGED" -> "任务内容或预算已变化，本次自动推进已停止。请结束本次制作，核对内容后重新确认。";
            case "COST_UNKNOWN" -> "存在尚未核实的费用，已暂停新增付费调用。";
            case "PROVIDER_CONFIGURATION_CHANGED" -> "制作配置已变化，已停止继续调用。请结束本次制作，重新估算并确认。";
            case "LIMIT_INSUFFICIENT" -> "后续步骤的估算超过本次剩余限额，已停止。调整范围后重新确认。";
            case "TIMING_NEEDS_DECISION" -> "实际素材时长与场景不符，请查看时长建议后调整。";
            case "MATERIALS_NEED_ATTENTION" -> "素材、字幕或配置尚未就绪，请查看故事板中的具体问题。";
            case "DISPATCH_FAILED" -> "本地制作连续恢复失败，已停止自动重试。请查看后端运行日志，处理后结束本次制作并重新确认。";
            default -> "制作需要处理，已保留运行和素材。请查看步骤结果，结束本次制作后调整或重新确认。";
        };
    }
    private void requireTask(String task,String owner) {if(media.countTaskByOwner(task,owner)!=1)throw conflict("创作任务不存在");}
    private void lock(String task,String owner) {if(media.lockMediaTask(task,owner).isEmpty())throw conflict("创作任务不存在");media.lockTaskDraftVersions(task,owner);}
}
