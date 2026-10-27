package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeReferenceIndexJobTest {
    private final KnowledgeRagProperties properties = new KnowledgeRagProperties();
    private final KnowledgeVectorStore stores = mock(KnowledgeVectorStore.class);
    private final KnowledgeReferenceIndexService videos = mock(KnowledgeReferenceIndexService.class);
    private final KnowledgeReferenceChunkIndexService chunks = mock(KnowledgeReferenceChunkIndexService.class);
    private final KnowledgeReferenceItemIndexService items = mock(KnowledgeReferenceItemIndexService.class);

    private KnowledgeReferenceIndexJob job() {
        properties.setEnabled(true);
        when(stores.isReady()).thenReturn(true);
        when(stores.isChunkReady()).thenReturn(true);
        when(stores.isChildReady()).thenReturn(true);
        when(videos.indexPending()).thenReturn(empty());
        when(chunks.indexPending()).thenReturn(empty());
        when(items.indexPending()).thenReturn(empty());
        return new KnowledgeReferenceIndexJob(properties, stores, videos, chunks, items);
    }

    @Test
    void disabledRagOrAutomaticIndexingDoesNotDispatch() {
        try (var job = job()) {
            properties.setEnabled(false);
            job.check();
            properties.setEnabled(true);
            properties.setAutoIndexEnabled(false);
            job.check();
        }
        verifyNoInteractions(stores);
        verify(videos, never()).indexPending();
        verify(chunks, never()).indexPending();
        verify(items, never()).indexPending();
    }

    @Test
    void slowEmbeddingDoesNotBlockSchedulerOrQueueDuplicateRounds() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var job = job()) {
            when(videos.indexPending()).thenAnswer(invocation -> {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                return empty();
            });
            try {
                job.check();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                // 此时第一轮仍阻塞在实际工作线程，调度入口必须能立即返回。
                job.check();
                job.check();
                verify(videos, times(1)).indexPending();
            } finally {
                release.countDown();
            }
            verify(items, timeout(2000)).indexPending();
        }
        verify(videos, times(1)).indexPending();
        verify(chunks, times(1)).indexPending();
        verify(items, times(1)).indexPending();
    }

    @Test
    void unavailableOrFailedLayerDoesNotPreventOtherLayers() {
        try (var job = job()) {
            when(stores.isReady()).thenReturn(false);
            when(chunks.indexPending()).thenThrow(new IllegalStateException("本轮中块不可用"));
            job.check();
            verify(items, timeout(2000)).indexPending();
        }
        verify(videos, never()).indexPending();
        verify(chunks, times(1)).indexPending();
    }

    @Test
    void shutdownPreventsFurtherDispatch() {
        var job = job();
        job.close();
        job.check();
        verifyNoInteractions(stores);
        verify(videos, never()).indexPending();
    }

    private ReferenceVideoIndexResponse empty() {
        return new ReferenceVideoIndexResponse(true, true, 0, 0, 0, 0, List.of(), LocalDateTime.now());
    }
}
