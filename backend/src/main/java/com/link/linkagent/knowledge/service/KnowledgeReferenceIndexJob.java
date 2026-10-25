package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 分批补齐三层待索引数据，耗时的向量化不占用媒体心跳共用的调度线程。 */
@Component
public class KnowledgeReferenceIndexJob implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceIndexJob.class);
    private final KnowledgeRagProperties properties;
    private final KnowledgeVectorStore stores;
    private final KnowledgeReferenceIndexService videos;
    private final KnowledgeReferenceChunkIndexService chunks;
    private final KnowledgeReferenceItemIndexService items;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("knowledge-auto-index").factory());

    public KnowledgeReferenceIndexJob(KnowledgeRagProperties properties, KnowledgeVectorStore stores,
                                     KnowledgeReferenceIndexService videos,
                                     KnowledgeReferenceChunkIndexService chunks,
                                     KnowledgeReferenceItemIndexService items) {
        this.properties = properties;
        this.stores = stores;
        this.videos = videos;
        this.chunks = chunks;
        this.items = items;
    }

    @Scheduled(initialDelayString = "${knowledge.rag.auto-index-interval-ms:30000}",
            fixedDelayString = "${knowledge.rag.auto-index-interval-ms:30000}")
    public void check() {
        if (stopping.get() || !properties.isEnabled() || !properties.isAutoIndexEnabled()
                || !running.compareAndSet(false, true)) return;
        try {
            executor.submit(() -> {
                try {
                    if (canContinue() && stores.isReady()) index("父卡片", videos::indexPending);
                    if (canContinue() && stores.isChunkReady()) index("主题中块", chunks::indexPending);
                    if (canContinue() && stores.isChildReady()) index("子条目", items::indexPending);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException error) {
            running.set(false);
            if (!stopping.get()) log.warn("知识库自动索引未能调度，类型={}", error.getClass().getSimpleName());
        }
    }

    private boolean canContinue() {
        return !stopping.get() && !Thread.currentThread().isInterrupted();
    }

    private void index(String layer, Supplier<ReferenceVideoIndexResponse> action) {
        try {
            var result = action.get();
            if (result.indexedCount() > 0 || result.failedCount() > 0) {
                log.info("知识库自动索引本批完成，层={}，成功={}，失败={}，未处理={}",
                        layer, result.indexedCount(), result.failedCount(), result.skippedCount());
            }
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().equals(HttpStatus.CONFLICT)) {
                log.debug("知识库索引正在手动处理，本轮跳过，层={}", layer);
            } else {
                log.warn("知识库自动索引本轮未执行，层={}，状态={}", layer, error.getStatusCode().value());
            }
        } catch (RuntimeException error) {
            // 某层暂时不可用时继续处理其它层，不把失败记录重新送进自动付费重试。
            log.warn("知识库自动索引本轮失败，层={}，类型={}", layer, error.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        stopping.set(true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
