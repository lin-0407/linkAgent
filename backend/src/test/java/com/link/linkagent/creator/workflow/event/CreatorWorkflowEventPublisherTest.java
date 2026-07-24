package com.link.linkagent.creator.workflow.event;

import com.link.linkagent.creator.workflow.model.CreatorWorkflowEventResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorWorkflowEventPublisherTest {

    @Test
    void shouldNotCompleteWithErrorAfterClientDisconnects() throws IOException {
        CreatorWorkflowEventPublisher publisher = new CreatorWorkflowEventPublisher();
        SseEmitter emitter = mock(SseEmitter.class);
        CreatorWorkflowEventResponse event = new CreatorWorkflowEventResponse(
                "event-1",
                "session-1",
                "task-1",
                "heartbeat",
                null,
                null,
                LocalDateTime.now()
        );
        doThrow(new IOException("Broken pipe"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        publisher.sendToEmitter(emitter, event);

        verify(emitter, never()).completeWithError(any(Throwable.class));
    }
}
