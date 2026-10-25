package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.storyboard.service.StoryboardProductionBridge;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(name={"creator.media.enabled","creator.creation.renderer.enabled","creator.creation.worker.enabled"},havingValue="true")
public class ExecutionWorker implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(ExecutionWorker.class);
    private final ExecutionMapper executions;
    private final ExecutionService service;
    private final StoryboardProductionBridge bridge;
    private final ExecutionSampleRenderer samples;
    private final ObjectStorageService storage;
    private final CreationResourceCleanup cleanup;
    private final AtomicBoolean busy=new AtomicBoolean(),stopping=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("creation-execution").factory());
    public ExecutionWorker(ExecutionMapper executions,ExecutionService service,StoryboardProductionBridge bridge,
            ExecutionSampleRenderer samples,ObjectStorageService storage,CreationResourceCleanup cleanup) {
        this.executions=executions;this.service=service;this.bridge=bridge;this.samples=samples;this.storage=storage;
        this.cleanup=cleanup;
    }
    @Scheduled(fixedDelayString="${creator.creation.worker.poll-interval-ms:2000}")
    public void poll() {
        if(stopping.get() || !busy.compareAndSet(false,true))return;
        try {executor.submit(()->{try {
            for(var e:executions.candidates()) {
                if(stopping.get())break;
                try {
                    var work=service.advance(e.taskId(),e.ownerId(),e.executionId());
                    if(work==null)continue;
                    var current=work.execution();
                    if("ATTACH".equals(work.action()))bridge.attachToRun(current.taskId(),current.ownerId(),current.storyboardId(),current.currentRunId(),()->stopping.get() || Thread.currentThread().isInterrupted());
                    if("SAMPLE".equals(work.action()))samples.render(current);
                } catch(ResponseStatusException error) {service.attention(e.taskId(),e.ownerId(),e.executionId(),"MATERIALS_NEED_ATTENTION");}
                catch(RuntimeException error) {log.warn("制作检查点等待恢复，制作={}，类型={}",e.executionId(),error.getClass().getSimpleName());service.dispatchFailed(e.taskId(),e.ownerId(),e.executionId());}
            }
            for(var object:executions.garbage()) {
                if(stopping.get())break;
                try {storage.deleteObject(object.bucketName(),object.objectKey());executions.deleted(object);}
                catch(RuntimeException error){log.info("未采纳样片对象等待清理，制作={}",object.executionId());}
            }
            cleanup.clean();
        } finally {busy.set(false);}});} catch(RuntimeException error){busy.set(false);}
    }
    @Override @PreDestroy public void close(){stopping.set(true);samples.close();executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
