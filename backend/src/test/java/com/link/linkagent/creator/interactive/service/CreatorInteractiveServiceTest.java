package com.link.linkagent.creator.interactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.common.DocumentExtractionService;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.llm.LLMService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreatorInteractiveServiceTest {

    @Test
    void shouldRestoreUploadedDocumentMetadataFromInteractiveSession() {
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setTaskId("task-1");
        session.setSessionId("interactive-session-1");
        session.setUploadedDocuments("""
                [{"documentId":"document-1","fileName":"选题资料.pdf","fileSize":245760,"contentType":"application/pdf"}]
                """);
        when(mapper.findSessionByTaskId("task-1")).thenReturn(Optional.of(session));
        when(mapper.listOptionsBySessionId("interactive-session-1")).thenReturn(List.of());

        CreatorInteractiveService service = new CreatorInteractiveService(
                mapper,
                mock(CreatorTaskService.class),
                mock(LLMService.class),
                new ObjectMapper(),
                mock(DocumentExtractionService.class)
        );

        var response = service.getInteractiveTask("task-1");

        assertThat(response.uploadedDocuments()).hasSize(1);
        assertThat(response.uploadedDocuments().getFirst().documentId()).isEqualTo("document-1");
        assertThat(response.uploadedDocuments().getFirst().fileName()).isEqualTo("选题资料.pdf");
        assertThat(response.uploadedDocuments().getFirst().fileSize()).isEqualTo(245760L);
        assertThat(response.uploadedDocuments().getFirst().contentType()).isEqualTo("application/pdf");
    }

    @Test
    void shouldKeepTaskRestorableWhenUploadedDocumentMetadataIsInvalid() {
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setTaskId("task-1");
        session.setSessionId("interactive-session-1");
        session.setBackgroundContext("已经提取的资料正文");
        session.setUploadedDocuments("invalid-json");
        when(mapper.findSessionByTaskId("task-1")).thenReturn(Optional.of(session));
        when(mapper.listOptionsBySessionId("interactive-session-1")).thenReturn(List.of());

        CreatorInteractiveService service = new CreatorInteractiveService(
                mapper,
                mock(CreatorTaskService.class),
                mock(LLMService.class),
                new ObjectMapper(),
                mock(DocumentExtractionService.class)
        );

        var response = service.getInteractiveTask("task-1");

        assertThat(response.backgroundContext()).isEqualTo("已经提取的资料正文");
        assertThat(response.uploadedDocuments()).isEmpty();
    }
}
