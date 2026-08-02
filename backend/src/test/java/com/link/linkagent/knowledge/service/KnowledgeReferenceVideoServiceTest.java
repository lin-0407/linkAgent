package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeReferenceVideoServiceTest {

    @Test
    void shouldUpdateSevenPublicFieldsAndRecomputeCurrentCategory() {
        KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
        KnowledgeQualityScoringService scoringService = mock(KnowledgeQualityScoringService.class);
        KnowledgeReferenceVideoService service = createService(mapper, scoringService);
        ReferenceVideoRecord current = record("video-1", "知识");
        ReferenceVideoRecord updated = record("video-1", "知识");
        updated.setCoverUrl("https://i0.hdslb.com/bfs/archive/cover.jpg");
        ReferenceVideoImportRequest.VideoStats stats = new ReferenceVideoImportRequest.VideoStats(
                100L, 20L, 5L, 8L, 3L, 6L);
        when(mapper.findByVideoId("video-1"))
                .thenReturn(Optional.of(current), Optional.of(updated));
        when(mapper.updatePublicMetadata(
                "video-1", updated.getCoverUrl(), 100L, 20L, 5L, 8L, 3L, 6L)).thenReturn(1);

        ReferenceVideoResponse response = service.updatePublicMetadata(
                "video-1", updated.getCoverUrl(), stats);

        assertThat(response.coverUrl()).isEqualTo(updated.getCoverUrl());
        verify(mapper).updatePublicMetadata(
                "video-1", updated.getCoverUrl(), 100L, 20L, 5L, 8L, 3L, 6L);
        verify(scoringService).recomputeCategories(Set.of("知识"));
    }

    @Test
    void shouldRejectIncompleteMetadataBeforeUpdatingDatabase() {
        KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
        KnowledgeQualityScoringService scoringService = mock(KnowledgeQualityScoringService.class);
        KnowledgeReferenceVideoService service = createService(mapper, scoringService);
        when(mapper.findByVideoId("video-1")).thenReturn(Optional.of(record("video-1", "知识")));
        ReferenceVideoImportRequest.VideoStats stats = new ReferenceVideoImportRequest.VideoStats(
                100L, 20L, 5L, 8L, 3L, null);

        assertThatThrownBy(() -> service.updatePublicMetadata(
                "video-1", "https://i0.hdslb.com/bfs/archive/cover.jpg", stats))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(mapper, never()).updatePublicMetadata(
                any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(scoringService);
    }

    private KnowledgeReferenceVideoService createService(KnowledgeReferenceVideoMapper mapper,
                                                         KnowledgeQualityScoringService scoringService) {
        return new KnowledgeReferenceVideoService(
                mapper,
                mock(KnowledgeReferenceCleaningService.class),
                mock(KnowledgeReferenceChunkService.class),
                scoringService,
                new ObjectMapper()
        );
    }

    private ReferenceVideoRecord record(String videoId, String category) {
        ReferenceVideoRecord record = new ReferenceVideoRecord();
        record.setVideoId(videoId);
        record.setCategory(category);
        return record;
    }
}
