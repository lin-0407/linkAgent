package com.link.linkagent.creator.creation.storyboard.service;

import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.FixedProjectJob;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.render.FixedProjectRenderService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 内部工程接入仍须已有整片授权和真实发布确认；准备授权不能通过此处升级为整片。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class StoryboardProductionBridge {
    private final StoryboardMapper boards;private final StoryboardPersistence persistence;private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;private final StoryboardProjectFiles files;private final CreationRuntimeMapper runs;
    private final FixedProjectJobMapper jobs;private final FixedProjectJobFiles fixedFiles;private final FixedProjectJobPersistenceService fixedJobs;private final MediaUploadMapper media;
    public StoryboardProductionBridge(StoryboardMapper boards,StoryboardPersistence persistence,StoryboardCodec codec,StoryboardCompiler compiler,
            StoryboardProjectFiles files,CreationRuntimeMapper runs,FixedProjectJobMapper jobs,FixedProjectJobFiles fixedFiles,FixedProjectJobPersistenceService fixedJobs,MediaUploadMapper media){this.boards=boards;this.persistence=persistence;this.codec=codec;this.compiler=compiler;this.files=files;this.runs=runs;this.jobs=jobs;this.fixedFiles=fixedFiles;this.fixedJobs=fixedJobs;this.media=media;}
    public FixedProjectJob attachToRun(String task,String owner,String storyboardId,String runId,BooleanSupplier cancelled){
        if(media.countTaskByOwner(task,owner)!=1)throw conflict("创作任务不存在");
        var board=boards.board(task,owner,storyboardId).orElseThrow(()->conflict("故事板不属于本任务"));var brief=persistence.brief(task,owner,board.briefId());
        var candidate=codec.candidate(codec.read(board.documentJson()).path("candidate"));var assets=persistence.frozen(board);
        var check=compiler.check(storyboardId,codec.read(brief.documentJson()),candidate,assets);if(!check.renderable())throw conflict("故事板仍有未准备完成的内容");
        var run=runs.run(task,owner,runId).orElseThrow(()->conflict("整片运行不存在"));var input=codec.read(run.requestJson());
        if(!"FULL".equals(run.kind()) || !storyboardId.equals(input.path("storyboardId").asText()) || !check.renderContentHash().equals(input.path("renderContentHash").asText()))throw conflict("整片授权与故事板版本或工程摘要不一致");
        var old=jobs.find(task,owner,runId);if(old.isPresent())return old.get();
        var workspace=runs.workspace(task,owner).orElseThrow();var approval=runs.approval(task,owner,run.approvalId()).orElseThrow();
        if(!runId.equals(workspace.activeRunId()) || !"QUEUED".equals(run.status()) || !"ACTIVE".equals(approval.status()) || !"PRODUCTION".equals(approval.approvalKind())
                || !run.inputFingerprint().equals(approval.inputFingerprint()))throw conflict("必须先确认本工程的整片制作范围");
        var project=compiler.compile(storyboardId,codec.read(brief.documentJson()),candidate,assets,runId,"board-"+UUID.randomUUID());
        FixedProjectRenderService.Request prepared=files.materialize(project,assets,cancelled);
        FixedProjectJob frozen;
        try{frozen=fixedFiles.freeze(task,owner,prepared);}
        catch(RuntimeException ex){files.discard(prepared);throw ex;}
        try{return fixedJobs.attach(frozen);}
        catch(RuntimeException ex){
            // 超时后暂未查到记录也可能仍在提交；只有另一个尝试已占据该运行时才可删除本尝试。
            java.util.Optional<FixedProjectJob> registered;
            try{registered=jobs.find(task,owner,runId);}catch(RuntimeException uncertain){throw ex;}
            if(registered.isPresent() && prepared.attemptId().equals(registered.get().sourceAttemptId()))return registered.get();
            if(registered.isPresent())files.discard(prepared);
            throw ex;
        }
    }
}
