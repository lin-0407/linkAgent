package com.link.linkagent.creator.creation.draft;

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 页面关闭后继续制作；每次只从已提交检查点恢复，不重新发送已受理的模型请求。 */
@Service
@ConditionalOnProperty(name={"creator.media.enabled","creator.creation.renderer.enabled","creator.creation.worker.enabled"},havingValue="true")
public class DraftWorker implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(DraftWorker.class);
    private final DraftMapper mapper;
    private final DraftService service;
    private final AtomicBoolean busy=new AtomicBoolean(),stopping=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("video-draft").factory());
    public DraftWorker(DraftMapper mapper,DraftService service){this.mapper=mapper;this.service=service;}
    @Scheduled(fixedDelayString="${creator.creation.worker.poll-interval-ms:2000}")
    public void poll() {
        if(stopping.get() || !busy.compareAndSet(false,true))return;
        try {executor.submit(()->{try {
            for(var row:mapper.candidates()) {
                if(stopping.get())break;
                try {service.advance(row.taskId(),row.ownerId(),row.draftId());}
                catch(ResponseStatusException error){service.blocked(row.taskId(),row.ownerId(),row.draftId(),error.getReason());}
                catch(RuntimeException error){log.warn("初稿检查点恢复失败，初稿={}，类型={}",row.draftId(),error.getClass().getSimpleName());service.dispatchFailed(row.draftId());}
            }
        } finally {busy.set(false);}});}catch(RuntimeException error){busy.set(false);}
    }
    @Override @PreDestroy public void close(){stopping.set(true);executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
