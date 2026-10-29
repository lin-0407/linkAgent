package com.link.linkagent.creator.creation.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.draft.DraftModels.*;
import com.link.linkagent.creator.creation.execution.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Snapshot;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 后台从持久化检查点推进初稿，不要求用户先填写策划表或确认发布方案。 */
@Service
@Transactional
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class DraftService {
    private static final Set<String> FINISHED=Set.of("COMPLETED","CANCELLED");
    private final DraftMapper drafts;
    private final MediaUploadMapper media;
    private final CreatorInteractiveMapper interactive;
    private final CreationRuntimeService runtime;
    private final StoryboardPersistence persistence;
    private final StoryboardService storyboards;
    private final StoryboardMapper boards;
    private final ExecutionService executions;
    private final StoryboardCodec codec;
    private final ObjectMapper json;
    private final CreationCapabilityGate capabilities;
    private final CreationVoiceProperties voice;
    private final CreatorMediaProperties mediaSettings;

    public DraftService(DraftMapper drafts,MediaUploadMapper media,CreatorInteractiveMapper interactive,
            CreationRuntimeService runtime,StoryboardPersistence persistence,StoryboardService storyboards,
            StoryboardMapper boards,ExecutionService executions,StoryboardCodec codec,ObjectMapper json,
            CreationCapabilityGate capabilities,CreationVoiceProperties voice,CreatorMediaProperties mediaSettings) {
        this.drafts=drafts;this.media=media;this.interactive=interactive;this.runtime=runtime;
        this.persistence=persistence;this.storyboards=storyboards;this.boards=boards;this.executions=executions;
        this.codec=codec;this.json=json;this.capabilities=capabilities;this.voice=voice;this.mediaSettings=mediaSettings;
    }

    public View start(String task,String owner,String key,Start input) {
        codec.valid(input);lock(task,owner);
        if(key==null || key.isBlank() || key.length()>128)throw conflict("制作请求需要非空幂等键，最多128字符");
        String hash=codec.hash(input);
        var replay=drafts.byKey(task,owner,key);
        if(replay.isPresent()) {
            if(!hash.equals(replay.get().requestHash()))throw conflict("同一幂等键不能修改创作要求");
            return view(replay.get());
        }
        runtime.checkConfiguration();
        var history=drafts.list(task,owner);
        var latest=history.isEmpty()?null:history.getFirst();
        if(latest!=null && !FINISHED.contains(latest.status()))throw conflict("上一版仍在制作或等待处理，请先完成或结束它");
        if(!Objects.equals(latest==null?null:latest.draftId(),input.baseDraftId()))throw conflict("视频版本已更新，请查看最新版本后再修改");
        if(latest!=null && input.instruction().isBlank())throw conflict("请写下这次想修改的内容");
        var snapshot=runtime.snapshot(task,owner);
        if(snapshot.workspaceRevision()!=input.expectedWorkspaceRevision() || snapshot.activeRun()!=null)throw conflict("工作区已变化或仍在制作，请刷新后重试");
        if(!snapshot.executionAvailable())throw conflict("自动制作执行器尚未启用，请先完成部署配置");
        // 内部命名空间用摘要隔离，外部合法的128字符键不能因追加前缀而越界。
        if(!snapshot.initialized())snapshot=runtime.initialize(task,owner,"draft-budget-"+codec.hash(key),snapshot.workspaceRevision(),input.budgetLimitCny());
        else if(snapshot.budget().limitCny().compareTo(input.budgetLimitCny())!=0)
            snapshot=runtime.updateBudget(task,owner,snapshot.workspaceRevision(),input.budgetLimitCny());
        if(snapshot.budget().paidCallsBlocked())throw conflict("预算已用尽或存在待核实费用，请先处理费用后再制作");
        String draftId=UUID.randomUUID().toString();
        var session=interactive.findSessionByTaskId(task).orElseThrow(()->conflict("请从视频想法入口创建任务"));
        ObjectNode context=json.createObjectNode().put("originalIdea",session.getIdea()).put("instruction",input.instruction());
        context.put("backgroundContext",Objects.toString(session.getBackgroundContext(),""));
        var feedback=context.putArray("feedbackHistory");
        var selectedAssets=new LinkedHashSet<>(input.assetIds());
        // 上一版简报保存完整反馈历史，分页不会遗失更早的用户要求。
        if(latest!=null) {
            var source=codec.read(persistence.brief(task,owner,latest.briefId()).sourceJson()).path("draft");
            if(source.path("feedbackHistory").isArray())source.path("feedbackHistory").forEach(feedback::add);
            var previous=latest.storyboardId()==null?null:boards.board(task,owner,latest.storyboardId()).orElse(null);
            if(previous!=null) {
                var candidate=codec.read(previous.documentJson()).path("candidate");
                context.set("previousCandidate",candidate);
                // 只复用上一版实际引用且仍就绪的生成素材，历次未使用的配音不挤占新简报。
                for(var scene:candidate.path("scenes"))for(String field:List.of("visualAssetId","narrationAssetId"))
                    if(scene.path(field).isTextual() && selectedAssets.size()<32) {
                        String assetId=scene.path(field).asText();
                        if(selectedAssets.contains(assetId))continue;
                        // 失效属于正常状态，直接查询，避免捕获嵌套事务异常后整笔制作仍被回滚。
                        if(drafts.readyAsset(task,owner,assetId)==1)selectedAssets.add(assetId);
                    }
            } else if(source.path("previousCandidate").isObject())context.set("previousCandidate",source.path("previousCandidate"));
        }
        if(!input.instruction().isBlank())feedback.add(input.instruction());
        boolean tts=capabilities.voiceEnabled() && voice.providerConfigured()
                && mediaSettings.getPreflight().getDashScopeApiKey()!=null && !mediaSettings.getPreflight().getDashScopeApiKey().isBlank();
        context.put("voiceAvailable",tts);
        String idea=session.getIdea();
        String constraints="先交付完整、可播放的创作版本；未指定的创作细节由AI选择并说明。严格保留用户原话中的限制。";
        var brief=persistence.saveDraftBrief(task,owner,"draft-brief-"+draftId,new BriefInput(snapshot.workspaceRevision(),
                abbreviate(idea,200),abbreviate(idea,500),"用户未指定时，本版先面向普通观众",input.targetDurationMs(),
                tts?"TTS":"NO_DIALOGUE","USER_PROVIDED",List.copyOf(selectedAssets),constraints),context);
        snapshot=runtime.snapshot(task,owner);
        BigDecimal limit=snapshot.budget().remainingCny();
        var quote=persistence.estimate(task,owner,new EstimateInput(snapshot.workspaceRevision(),brief.briefId()));
        BigDecimal preparation=(BigDecimal)quote.get("estimatedMaxAdditionalCostCny");
        if(preparation.compareTo(limit)>0)throw conflict("本次准备费用估算超过剩余预算，请调整预算后再制作");
        var job=persistence.generate(task,owner,"draft-prepare-"+draftId,new Generate(snapshot.workspaceRevision(),brief.briefId(),preparation));
        var row=new Draft(draftId,task,owner,latest==null?1:Math.addExact(latest.version(),1),input.baseDraftId(),codec.write(input),hash,key,
                "PREPARING",brief.briefId(),job.runId(),null,null,runtime.snapshot(task,owner).workspaceRevision(),limit,null,0,null);
        drafts.insert(row);
        return view(require(task,owner,draftId));
    }

    @Transactional(readOnly=true)
    public Workspace workspace(String task,String owner) {
        var snapshot=runtime.snapshot(task,owner);
        return new Workspace(drafts.list(task,owner).stream().map(this::view).toList(),snapshot);
    }

    public void advance(String task,String owner,String id) {
        lock(task,owner);var row=require(task,owner,id);
        if(FINISHED.contains(row.status()) || "WAITING_USER".equals(row.status()))return;
        if("CANCEL_REQUESTED".equals(row.status())) {
            boolean done=row.executionId()!=null?"CANCELLED".equals(executions.get(task,owner,row.executionId()).status())
                    :Set.of("COMPLETED","CANCELLED","FAILED").contains(runtime.getRun(task,owner,row.preparationRunId()).status());
            if(done)update(row,"CANCELLED",row.storyboardId(),row.executionId(),null);
            return;
        }
        if("PREPARING".equals(row.status())) {
            var run=runtime.getRun(task,owner,row.preparationRunId());
            if(Set.of("FAILED","CANCELLED").contains(run.status())) {
                update(row,"WAITING_USER",null,null,"初稿准备未完成，已保留原要求和费用记录。请结束本次制作后重试。"+Objects.toString(run.errorCode(),""));return;
            }
            if(!"COMPLETED".equals(run.status()))return;
            var job=boards.job(task,owner,row.preparationRunId()).orElseThrow();
            if(job.storyboardId()==null)throw conflict("初稿准备没有产出可制作的工程");
            var snapshot=runtime.snapshot(task,owner);
            if(snapshot.workspaceRevision()!=row.expectedWorkspaceRevision()+1)throw conflict("制作期间内容或预算已变化，请结束本次制作后重新确认");
            var quote=executions.estimate(task,owner,new ExecutionModels.EstimateInput(snapshot.workspaceRevision(),job.storyboardId(),"DRAFT"));
            if(!quote.blockers().isEmpty()) {update(row,"WAITING_USER",job.storyboardId(),null,quote.blockers().getFirst());return;}
            var preparation=runtimeTotals(task,owner,row.preparationRunId());
            if(preparation.unknownCny().signum()>0 || preparation.overrunCount()>0)throw conflict("初稿准备存在待核实或超出估算的费用，已暂停新增付费调用");
            BigDecimal remaining=row.authorizationLimitCny().subtract(preparation.occupied()).min(snapshot.budget().remainingCny());
            if(remaining.compareTo(quote.estimatedMaxAdditionalCostCny())<0)throw conflict("制作费用估算超过本版剩余授权，已暂停，请调整预算后重新制作");
            var execution=executions.start(task,owner,"draft-execute-"+id,new ExecutionModels.Start(snapshot.workspaceRevision(),job.storyboardId(),
                    quote.storyboardHash(),"DRAFT",quote.providerFingerprint(),remaining));
            update(row,"EXECUTING",job.storyboardId(),execution.executionId(),null);
        } else if("EXECUTING".equals(row.status())) {
            var execution=executions.get(task,owner,row.executionId());
            String status=switch(execution.status()){case "COMPLETED"->"COMPLETED";case "CANCELLED"->"CANCELLED";case "WAITING_USER"->"WAITING_USER";default->"EXECUTING";};
            update(row,status,execution.storyboardId(),row.executionId(),execution.message());
        }
    }

    public View cancel(String task,String owner,String id) {
        lock(task,owner);var row=require(task,owner,id);
        if(FINISHED.contains(row.status()))return view(row);
        String status;
        if(row.executionId()!=null)status=executions.cancel(task,owner,row.executionId()).status();
        else status=runtime.cancel(task,owner,row.preparationRunId()).status();
        update(row,Set.of("COMPLETED","CANCELLED","FAILED").contains(status)?"CANCELLED":"CANCEL_REQUESTED",row.storyboardId(),row.executionId(),null);
        return view(require(task,owner,id));
    }

    public void blocked(String task,String owner,String id,String message) {
        lock(task,owner);var row=require(task,owner,id);
        if(!FINISHED.contains(row.status()))update(row,"WAITING_USER",row.storyboardId(),row.executionId(),abbreviate(message,500));
    }
    public void dispatchFailed(String id) {drafts.dispatchFailed(id);}

    private com.link.linkagent.creator.creation.model.CreationRuntimeModels.Totals runtimeTotals(String task,String owner,String run) {
        return drafts.preparationTotals(task,owner,run);
    }
    private View view(Draft row) {
        List<String> choices=new ArrayList<>(),notes=new ArrayList<>();
        var input=codec.read(row.requestJson());
        if(row.storyboardId()!=null) {
            // 配音和时长处理会派生工程，作品说明仍读取本轮文本生成时留下的选择与自查。
            var source=boards.job(row.taskId(),row.ownerId(),row.preparationRunId()).orElseThrow();
            var board=storyboards.board(row.taskId(),row.ownerId(),source.storyboardId());
            board.document().path("candidate").path("creativeChoices").forEach(n->choices.add(n.asText()));
            board.document().path("candidate").path("reviewNotes").forEach(n->notes.add(n.asText()));
        }
        var current=row.storyboardId()==null?null:boards.board(row.taskId(),row.ownerId(),row.storyboardId()).orElse(null);
        var brief=persistence.brief(row.taskId(),row.ownerId(),current==null?row.briefId():current.briefId());
        if("NO_DIALOGUE".equals(codec.read(brief.documentJson()).path("dialogueMode").asText()))notes.add("本版采用无配音的图文与素材组合，可看完后再补充声音。" );
        notes.add("以上为制作初稿和AI自查，正式发布前还需完整试映。" );
        var execution=row.executionId()==null?null:executions.get(row.taskId(),row.ownerId(),row.executionId());
        return new View(row.draftId(),row.version(),row.baseDraftId(),row.idempotencyKey(),input.path("instruction").asText(),row.status(),codec.read(brief.documentJson()).path("targetDurationMs").asLong(),row.errorMessage(),
                row.storyboardId(),List.copyOf(choices),List.copyOf(notes),execution,row.createTime());
    }
    private Draft require(String task,String owner,String id){return drafts.find(task,owner,id).orElseThrow(()->conflict("视频版本不存在"));}
    private void update(Draft row,String status,String board,String execution,String message){drafts.update(row.draftId(),status,board,execution,message);}
    private void lock(String task,String owner){media.lockMediaTask(task,owner).orElseThrow(()->conflict("创作任务不存在"));}
    private static String abbreviate(String text,int max){return text.length()<=max?text:text.substring(0,max);}
}
