package com.link.linkagent.creator.workflow.event;

import com.link.linkagent.creator.workflow.model.CreatorWorkflowEventResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 创作者工作流 SSE 发布器。
 * 这里不保存业务状态，只维护内存连接；真实状态仍以 MySQL 消息和步骤表为准。
 */
@Component
public class CreatorWorkflowEventPublisher {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    public SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitterMap.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(error -> removeEmitter(sessionId, emitter));
        return emitter;
    }

    public void sendToEmitter(SseEmitter emitter, CreatorWorkflowEventResponse event) {
        try {
            send(emitter, event);
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    public void publish(String sessionId, CreatorWorkflowEventResponse event) {
        List<SseEmitter> emitters = emitterMap.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event);
            } catch (IOException exception) {
                emitter.completeWithError(exception);
                removeEmitter(sessionId, emitter);
            }
        }
    }

    private void send(SseEmitter emitter, CreatorWorkflowEventResponse event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.eventId())
                .name(event.eventType())
                .data(event));
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterMap.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emitterMap.remove(sessionId);
        }
    }
}
