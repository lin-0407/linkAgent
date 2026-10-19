package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeReferenceImportTransactionTest {
    @Test void cleaningRunsOutsideTransactionAndFactsRunInsideIt() throws Exception {
        var mapper = mock(KnowledgeReferenceVideoMapper.class);
        var cleaning = mock(KnowledgeReferenceCleaningService.class);
        var chunks = mock(KnowledgeReferenceChunkService.class);
        when(cleaning.clean(anyString(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new KnowledgeReferenceCleaningService.CleaningResult(List.of(),"测试摘要");
        });
        when(chunks.buildChunks(any(),anyList())).thenReturn(List.of());
        when(mapper.findActiveByBvId(anyString())).thenReturn(Optional.empty());
        when(mapper.insertReferenceVideo(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        var service = service(mapper, cleaning, chunks);
        assertThat(service.importReferenceVideos(request()).importedCount()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void existingBvRefreshesWithoutCleaningOrReplacingContent() throws Exception {
        var mapper = mock(KnowledgeReferenceVideoMapper.class);
        var cleaning = mock(KnowledgeReferenceCleaningService.class);
        var chunks = mock(KnowledgeReferenceChunkService.class);
        var old = new ReferenceVideoRecord();
        old.setVideoId("original-video"); old.setCategory("知识");
        when(mapper.countByBvId(anyString())).thenReturn(1L);
        when(mapper.findActiveByBvId(anyString())).thenReturn(Optional.of(old));
        when(mapper.updateCollectedMetadata(eq("original-video"),any())).thenReturn(1);
        var result = service(mapper,cleaning,chunks).importReferenceVideos(request());
        assertThat(result.updatedCount()).isEqualTo(1);
        verifyNoInteractions(cleaning,chunks);
        verify(mapper,never()).insertReferenceVideo(any());
    }

    @Test void concurrentDuplicateRetriesFactsWithoutAnotherModelCall() throws Exception {
        var mapper = mock(KnowledgeReferenceVideoMapper.class);
        var cleaning = mock(KnowledgeReferenceCleaningService.class);
        var chunks = mock(KnowledgeReferenceChunkService.class);
        var old = new ReferenceVideoRecord(); old.setVideoId("racing-video");
        when(cleaning.clean(anyString(),anyString(),anyList(),anyList())).thenReturn(new KnowledgeReferenceCleaningService.CleaningResult(List.of(),null));
        when(chunks.buildChunks(any(),anyList())).thenReturn(List.of());
        when(mapper.findActiveByBvId(anyString())).thenReturn(Optional.empty(),Optional.of(old));
        when(mapper.insertReferenceVideo(any())).thenThrow(new DuplicateKeyException("同BV已被另一请求提交"));
        when(mapper.updateCollectedMetadata(anyString(),any())).thenReturn(0);
        var result = service(mapper,cleaning,chunks).importReferenceVideos(request());
        assertThat(result.skippedCount()).isEqualTo(1);
        verify(cleaning,times(1)).clean(anyString(),anyString(),anyList(),anyList());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private KnowledgeReferenceVideoService service(KnowledgeReferenceVideoMapper mapper, KnowledgeReferenceCleaningService cleaning,
            KnowledgeReferenceChunkService chunks) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(source.getConnection()).thenReturn(connection);
        return new KnowledgeReferenceVideoService(mapper,cleaning,chunks,mock(KnowledgeQualityScoringService.class),
                new ObjectMapper(),new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private ReferenceVideoImportRequest request() {
        var now = LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE);
        var stats = new ReferenceVideoImportRequest.VideoStats(80000L,5000L,3000L,6000L,100L,300L);
        var video = new ReferenceVideoImportRequest.VideoItem("BV1uVpn6eEhV",null,"近期视频",null,null,"知识",null,stats,List.of(),List.of(),
                now.minusDays(2).atZone(ReferenceVideoCollectionPolicy.ZONE).toEpochSecond(),now.atZone(ReferenceVideoCollectionPolicy.ZONE).toEpochSecond());
        return new ReferenceVideoImportRequest("bilibili_auto","BENCHMARK",null,List.of(video),60.0);
    }
}
