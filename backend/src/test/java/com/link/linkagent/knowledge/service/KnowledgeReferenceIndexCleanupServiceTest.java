package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.model.ReferenceIndexCleanupRecord;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeReferenceIndexCleanupServiceTest {
    @Test void deletesFiveCollectionsEvenWhenRagIsDisabledAndClosesClient() throws Exception {
        var properties = new KnowledgeRagProperties();
        assertThat(properties.isEnabled()).isFalse();
        try (var construction = mockConstruction(MilvusClientV2.class,(client,context) -> when(client.hasCollection(any())).thenReturn(true))) {
            new KnowledgeReferenceIndexCleanupService(properties,new ObjectMapper()).delete(new ReferenceIndexCleanupRecord("video-id","[\"item-id\"]","[\"chunk-id\"]"));
            var client = construction.constructed().getFirst();
            var requests = ArgumentCaptor.forClass(DeleteReq.class);
            verify(client,times(5)).delete(requests.capture());
            assertThat(requests.getAllValues().stream().map(DeleteReq::getCollectionName).toList()).containsExactly(
                    properties.getCollectionName(),properties.getChildCollectionName(),properties.getChunkCollectionName(),
                    properties.getHybrid().getCollectionName(),properties.getHybrid().getChildCollectionName());
            assertThat(requests.getAllValues().stream().map(DeleteReq::getIds).toList()).containsExactly(
                    List.of("video-id"),List.of("item-id"),List.of("chunk-id"),List.of("video-id"),List.of("item-id"));
            verify(client).close();
        }
    }
    @Test void collectionFailurePropagatesForPersistentRetryAndStillClosesClient() throws Exception {
        try (var construction = mockConstruction(MilvusClientV2.class,(client,context) -> when(client.hasCollection(any())).thenThrow(new IllegalStateException("暂时断线")))) {
            assertThatThrownBy(() -> new KnowledgeReferenceIndexCleanupService(new KnowledgeRagProperties(),new ObjectMapper())
                    .delete(new ReferenceIndexCleanupRecord("video-id","[]","[]"))).hasMessageContaining("暂时断线");
            verify(construction.constructed().getFirst()).close();
        }
    }
}
