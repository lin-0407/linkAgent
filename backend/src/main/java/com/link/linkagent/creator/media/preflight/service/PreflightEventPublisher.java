package com.link.linkagent.creator.media.preflight.service;

import com.link.linkagent.creator.media.preflight.model.PreflightEventResponse;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发布前试映 SSE 通知器。
 * 数据库快照才是事实来源；连接中断或服务重启后由页面重新 GET，不依赖事件历史回放。
 */
@Component
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightEventPublisher {

    private static final long SSE_TIMEOUT_MILLIS = 90_000L;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(PreflightReviewResponse snapshot) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(snapshot.reviewId(), ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(snapshot.reviewId(), emitter));
        emitter.onTimeout(() -> remove(snapshot.reviewId(), emitter));
        emitter.onError(ignored -> remove(snapshot.reviewId(), emitter));
        send(emitter, "snapshot", event(snapshot, "snapshot"));
        return emitter;
    }

    public void publish(PreflightReviewResponse snapshot, String eventType) {
        List<SseEmitter> reviewEmitters = emitters.get(snapshot.reviewId());
        if (reviewEmitters == null) return;
        PreflightEventResponse event = event(snapshot, eventType);
        for (SseEmitter emitter : reviewEmitters) {
            send(emitter, eventType, event);
        }
    }

    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        for (Map.Entry<String, List<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("reviewId", entry.getKey())));
                } catch (IOException | IllegalStateException exception) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private PreflightEventResponse event(PreflightReviewResponse snapshot, String eventType) {
        return new PreflightEventResponse(
                snapshot.reviewId() + ":" + snapshot.eventSequence(),
                snapshot.taskId(),
                snapshot.reviewId(),
                snapshot.eventSequence(),
                eventType,
                OffsetDateTime.now(),
                Map.of(
                        "status", snapshot.status(),
                        "currentStep", snapshot.currentStep(),
                        "progressPercent", snapshot.progressPercent()
                )
        );
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException exception) {
            emitter.complete();
        }
    }

    private void remove(String reviewId, SseEmitter emitter) {
        List<SseEmitter> reviewEmitters = emitters.get(reviewId);
        if (reviewEmitters == null) return;
        reviewEmitters.remove(emitter);
        if (reviewEmitters.isEmpty()) emitters.remove(reviewId);
    }
}
