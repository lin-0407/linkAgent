package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.creation.render.RenderExecutionException;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.probe.service.FfprobeMediaProbeService;
import com.link.linkagent.creator.media.probe.service.MediaProbeException;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratedDraftProbeServiceTest {
    final CreatorMediaProperties properties = new CreatorMediaProperties();
    final ObjectStorageService storage = mock(ObjectStorageService.class);
    final FfprobeMediaProbeService ffprobe = mock(FfprobeMediaProbeService.class);
    final GeneratedDraftProbePersistenceService persistence = mock(GeneratedDraftProbePersistenceService.class);
    final GeneratedDraftLease lease = new GeneratedDraftLease("task", "default", "run", "worker", 1);
    final DraftVideoRecord draft = mock(DraftVideoRecord.class);
    final MediaProbeResult result = new MediaProbeResult(2000, 1280, 720, new BigDecimal("24"), "h264", "aac", true);
    GeneratedDraftProbeService service;

    @BeforeEach void setup() {
        properties.setEnabled(true);
        service = new GeneratedDraftProbeService(properties, storage, ffprobe, persistence);
        when(draft.bucketName()).thenReturn("private"); when(draft.objectKey()).thenReturn("run/video.mp4");
        when(persistence.begin(lease, "version")).thenReturn(new GeneratedDraftProbePersistenceService.ProbeClaim(draft, "creation-1-token"));
    }

    @Test void alreadyReadyUsesPersistedFactsWithoutStorageOrNativeProcess() {
        when(persistence.begin(lease, "version")).thenReturn(new GeneratedDraftProbePersistenceService.ProbeClaim(draft, null));
        assertThat(service.probe(lease, "version", () -> false)).isSameAs(draft);
        verifyNoInteractions(storage, ffprobe);
    }

    @Test void cancelledBeforeClaimDoesNotTouchMedia() {
        assertThatThrownBy(() -> service.probe(lease, "version", () -> true)).isInstanceOf(RenderExecutionException.class);
        verifyNoInteractions(persistence, storage, ffprobe);
    }

    @Test void sourceResultMustPassPersistenceFenceBeforeBeingReturned() {
        signed(); when(ffprobe.probe(eq("https://private.invalid/source"), any())).thenReturn(result);
        when(persistence.complete(lease, "version", "creation-1-token", result)).thenReturn(draft);
        assertThat(service.probe(lease, "version", () -> false)).isSameAs(draft);
        var order = inOrder(persistence, storage, ffprobe);
        order.verify(persistence).begin(lease, "version");
        order.verify(storage).presignGetObject(eq("private"), eq("run/video.mp4"), any());
        order.verify(ffprobe).probe(eq("https://private.invalid/source"), any());
        order.verify(persistence).complete(lease, "version", "creation-1-token", result);
    }

    @Test void nativeFailureIsFencedAndDoesNotExposeSignedUrl() {
        signed(); when(ffprobe.probe(anyString(), any())).thenThrow(new MediaProbeException(HttpStatus.GATEWAY_TIMEOUT, "secret signed URL"));
        assertThatThrownBy(() -> service.probe(lease, "version", () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_PROBE_FAILED"))
                .hasMessageNotContaining("secret");
        verify(persistence).fail(lease, "version", "creation-1-token");
        verify(persistence, never()).complete(any(), anyString(), anyString(), any());
    }

    @Test void cancellationDuringProbeDoesNotWriteFailureWithOldExecutionRight() {
        signed(); AtomicBoolean cancelled = new AtomicBoolean();
        when(ffprobe.probe(anyString(), any())).thenAnswer(call -> {
            cancelled.set(true); throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "已停止");
        });
        assertThatThrownBy(() -> service.probe(lease, "version", cancelled::get))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("CANCELLED"));
        verify(persistence, never()).fail(any(), anyString(), anyString());
        verify(persistence, never()).complete(any(), anyString(), anyString(), any());
    }

    @Test void mismatchedSourceDoesNotBecomeReady() {
        signed(); when(ffprobe.probe(anyString(), any())).thenReturn(result);
        when(persistence.complete(lease, "version", "creation-1-token", result))
                .thenThrow(new RenderExecutionException("SOURCE_PROBE_MISMATCH", "实测不符"));
        assertThatThrownBy(() -> service.probe(lease, "version", () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_PROBE_MISMATCH"));
        verify(persistence).fail(lease, "version", "creation-1-token");
    }

    private void signed() {
        var read = mock(PresignedObjectRead.class); when(read.url()).thenReturn("https://private.invalid/source");
        when(storage.presignGetObject(eq("private"), eq("run/video.mp4"), any())).thenReturn(read);
    }
}
