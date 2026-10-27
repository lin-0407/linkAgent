package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 样片和完整初稿使用独立对象；只有实际核验的产物能播放，不提前推进发布门禁。 */
@Service
@ConditionalOnProperty(name={"creator.media.enabled","creator.creation.renderer.enabled","creator.creation.worker.enabled"},havingValue="true")
public class ExecutionSampleRenderer implements AutoCloseable {
    private final CreationRuntimeService runtime;
    private final ExecutionService service;
    private final ExecutionMapper executions;
    private final StoryboardMapper boards;
    private final StoryboardPersistence persistence;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final StoryboardProjectFiles files;
    private final FixedProjectRenderService renderer;
    private final ObjectStorageService storage;
    private final ObjectStorageProperties properties;
    private final ScheduledExecutorService pulses=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("sample-heartbeat").factory());
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final String worker="sample-"+UUID.randomUUID();
    public ExecutionSampleRenderer(CreationRuntimeService runtime,ExecutionService service,ExecutionMapper executions,
            StoryboardMapper boards,StoryboardPersistence persistence,StoryboardCodec codec,StoryboardCompiler compiler,
            StoryboardProjectFiles files,FixedProjectRenderService renderer,ObjectStorageService storage,ObjectStorageProperties properties) {
        this.runtime=runtime;this.service=service;this.executions=executions;this.boards=boards;this.persistence=persistence;
        this.codec=codec;this.compiler=compiler;this.files=files;this.renderer=renderer;this.storage=storage;this.properties=properties;
    }
    public void render(Execution execution) {
        var claim=runtime.claim(execution.taskId(),execution.ownerId(),execution.currentRunId(),worker,120);
        if(claim.isEmpty())return;
        Run run=claim.get();
        if("CANCEL_REQUESTED".equals(run.status())) {runtime.acknowledgeCancellation(run.taskId(),run.ownerId(),run.runId(),worker,run.leaseGeneration());return;}
        var lost=new AtomicBoolean();
        var pulse=pulses.scheduleAtFixedRate(()->{
            try {if(!runtime.heartbeat(run.taskId(),run.ownerId(),run.runId(),worker,run.leaseGeneration(),120)
                    || !"RUNNING".equals(runtime.getRun(run.taskId(),run.ownerId(),run.runId()).status()))lost.set(true);}
            catch(RuntimeException error){lost.set(true);}
        },0,2,TimeUnit.SECONDS);
        FixedProjectRenderService.Request request=null;
        BooleanSupplier cancelled=()->lost.get() || stopping.get() || Thread.currentThread().isInterrupted();
        try {
            if(run.attemptCount()>3)throw new RenderExecutionException("ATTEMPTS_EXHAUSTED","样片恢复次数已达上限");
            var board=boards.board(run.taskId(),run.ownerId(),execution.storyboardId()).orElseThrow();
            var brief=persistence.brief(run.taskId(),run.ownerId(),board.briefId());
            var scope=StoryboardSelection.scope(codec.read(brief.documentJson()),codec.candidate(codec.read(board.documentJson()).path("candidate")),service.sceneIds(execution));
            var assets=persistence.frozen(board);
            var project=compiler.compile(board.storyboardId(),scope.brief(),scope.candidate(),assets,run.runId(),"sample-"+UUID.randomUUID());
            request=files.materialize(project,assets,cancelled);
            var output=renderer.render(request,cancelled);
            var planned=new ArrayList<Output>();
            for(var entry:output.files().entrySet()) {
                var artifact=entry.getValue();
                String family="DRAFT".equals(execution.kind())?"drafts":"samples";
                String key="users/"+run.ownerId()+"/tasks/"+run.taskId()+"/"+family+"/"+execution.executionId()+"/"+run.leaseGeneration()+"/"+entry.getKey();
                var object=new Output(execution.executionId(),run.leaseGeneration(),entry.getKey(),properties.getBucket(),key,
                        artifact.bytes(),artifact.sha256(),artifact.contentType(),false,false);
                executions.plan(object);planned.add(object);
            }
            for(var object:planned) {
                check(cancelled);
                storage.putObject(object.bucketName(),object.objectKey(),output.files().get(object.fileName()).path(),object.contentType());
                check(cancelled);
                var head=storage.headObject(object.bucketName(),object.objectKey());
                if(head.contentLength()!=object.bytes() || !object.contentType().equalsIgnoreCase(head.contentType()))
                    throw new RenderExecutionException("SAMPLE_STORAGE_INVALID","样片存储对象与渲染结果不一致");
                Path verification=output.directory().resolve("verify-"+object.fileName().replace('/','_'));
                try {
                    storage.downloadObject(object.bucketName(),object.objectKey(),verification);
                    if(Files.size(verification)!=object.bytes() || !digest(verification).equals(object.sha256()))
                        throw new RenderExecutionException("SAMPLE_STORAGE_INVALID","样片读取内容摘要不一致");
                } finally {Files.deleteIfExists(verification);}
            }
            check(cancelled);
            service.completeSample(run.taskId(),run.ownerId(),execution.executionId(),run,worker);
        } catch(Exception error) {
            if(!stopping.get()) {
                var current=runtime.getRun(run.taskId(),run.ownerId(),run.runId());
                if("CANCEL_REQUESTED".equals(current.status()))runtime.acknowledgeCancellation(run.taskId(),run.ownerId(),run.runId(),worker,run.leaseGeneration());
                else if(!lost.get())runtime.fail(run.taskId(),run.ownerId(),run.runId(),worker,run.leaseGeneration(),
                        error instanceof RenderExecutionException render?render.code():"SAMPLE_RENDER_FAILED");
            }
        } finally {
            pulse.cancel(false);
            if(request!=null)files.discard(request);
        }
    }
    private static void check(BooleanSupplier cancelled) {if(cancelled.getAsBoolean())throw new RenderExecutionException("CANCELLED","样片执行已停止");}
    private static String digest(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int size;while((size=input.read(buffer))!=-1)digest.update(buffer,0,size);}return HexFormat.of().formatHex(digest.digest());
    }
    @Override public void close(){stopping.set(true);pulses.shutdownNow();}
}
