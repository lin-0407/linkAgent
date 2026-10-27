package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeReferenceAutomaticIndexTest {
    private final KnowledgeRagProperties properties = new KnowledgeRagProperties();
    private final KnowledgeVectorStore stores = mock(KnowledgeVectorStore.class);
    private final KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
    private final VectorStore vectorStore = mock(VectorStore.class);

    private KnowledgeReferenceIndexService service() {
        properties.setEnabled(true);
        when(stores.isReady()).thenReturn(true);
        when(stores.getVectorStore()).thenReturn(Optional.of(vectorStore));
        return new KnowledgeReferenceIndexService(properties, mapper, stores);
    }

    @Test
    void subsequentBatchesContinuePastTwoHundredWithoutReembeddingSuccessfulRows() {
        var service = service();
        var first = IntStream.range(0, 200).mapToObj(this::video).toList();
        var second = IntStream.range(200, 237).mapToObj(this::video).toList();
        when(mapper.listIndexableVideos(200, false)).thenReturn(first, second, List.of());
        assertThat(service.indexPending().indexedCount()).isEqualTo(200);
        assertThat(service.indexPending().indexedCount()).isEqualTo(37);
        assertThat(service.indexPending().requestedCount()).isZero();
        verify(mapper, times(237)).updateVideoEmbeddingIndexed(anyString(), anyString());
        verify(mapper, never()).listIndexableVideos(anyInt());
    }

    @Test
    void emptyAutomaticBatchesForAllLayersDoNotCallEmbedding() {
        var service = service();
        when(stores.isChunkReady()).thenReturn(true);
        when(stores.isChildReady()).thenReturn(true);
        when(stores.getChunkVectorStore()).thenReturn(Optional.of(vectorStore));
        when(stores.getChildVectorStore()).thenReturn(Optional.of(vectorStore));
        var chunks = new KnowledgeReferenceChunkIndexService(properties, mapper, stores, mock(KnowledgeReferenceChunkService.class));
        var items = new KnowledgeReferenceItemIndexService(properties, mapper, stores);
        assertThat(service.indexPending().requestedCount()).isZero();
        assertThat(chunks.indexPending().requestedCount()).isZero();
        assertThat(items.indexPending().requestedCount()).isZero();
        verify(mapper).listIndexableVideos(200, false);
        verify(mapper).listIndexableChunks(200, false);
        verify(mapper).listIndexableItems(200, false);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void failedAutomaticRowsAreRecordedAndNotExplicitlyRetriedByNextRound() {
        var service = service();
        when(mapper.listIndexableVideos(200, false)).thenReturn(List.of(video(1)), List.of());
        doThrow(new IllegalStateException("向量化失败")).when(vectorStore).add(anyList());
        assertThat(service.indexPending().failedCount()).isEqualTo(1);
        assertThat(service.indexPending().requestedCount()).isZero();
        verify(mapper).updateVideoEmbeddingFailed(eq("video-1"), anyString());
        verify(vectorStore, times(1)).add(anyList());
        verify(mapper, never()).listIndexableVideos(anyInt());
    }

    @Test
    void manualRebuildCannotDuplicateAnActiveAutomaticBatch() throws Exception {
        var service = service();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(mapper.listIndexableVideos(200, false)).thenReturn(List.of(video(1)));
        doAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return null;
        }).when(vectorStore).add(anyList());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var automatic = executor.submit(service::indexPending);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.rebuild(null)).isInstanceOfSatisfying(
                        ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
            } finally {
                release.countDown();
            }
            assertThat(automatic.get(2, TimeUnit.SECONDS).indexedCount()).isEqualTo(1);
        }
        verify(vectorStore, times(1)).add(anyList());
        verify(mapper, never()).listIndexableVideos(anyInt());
    }

    @Test
    void manualMapperEntryStillIncludesFailedRows() {
        var mapper = mock(KnowledgeReferenceVideoMapper.class, CALLS_REAL_METHODS);
        var rows = List.of(video(1));
        when(mapper.listIndexableVideos(200, true)).thenReturn(rows);
        assertThat(mapper.listIndexableVideos(200)).isSameAs(rows);
        verify(mapper).listIndexableVideos(200, true);
    }

    private ReferenceVideoRecord video(int id) {
        var video = new ReferenceVideoRecord();
        video.setVideoId("video-" + id);
        video.setTitle("自动索引案例 " + id);
        video.setTier("BENCHMARK");
        video.setSource("TEST");
        return video;
    }
}
