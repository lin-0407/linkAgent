package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.render.RenderExecutionException;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证探测协议及事实比对；事务回滚、竞争和接管由真实 MySQL 集成测试验证。 */
class GeneratedDraftProbePersistenceServiceTest {
    final CreationRuntimeService execution = mock(CreationRuntimeService.class);
    final CreationRuntimeMapper runtime = mock(CreationRuntimeMapper.class);
    final GeneratedDraftMapper generated = mock(GeneratedDraftMapper.class);
    final FixedProjectJobMapper jobs = mock(FixedProjectJobMapper.class);
    final MediaUploadMapper media = mock(MediaUploadMapper.class);
    final ObjectMapper json = new ObjectMapper();
    final GeneratedDraftLease lease = new GeneratedDraftLease("task", "default", "run", "worker", 2);
    final DraftVideoRecord draft = mock(DraftVideoRecord.class);
    final GeneratedDraftAttempt attempt = mock(GeneratedDraftAttempt.class);
    final String project = "{\"schemaVersion\":2,\"video\":{\"width\":1280,\"height\":720,\"fps\":24,\"frameCount\":48}}";
    final GeneratedDraftProbePersistenceService service = new GeneratedDraftProbePersistenceService(execution, runtime, generated, jobs, media, json);

    @BeforeEach void setup() {
        var input = new GeneratedDraftInput("session", "result", "plan", null, "board", CreationInputRules.renderContentHash(json, project));
        when(execution.requireProductionLease(lease)).thenReturn(input);
        when(attempt.versionId()).thenReturn("version");
        when(generated.registered("task", "default", "run")).thenReturn(Optional.of(attempt));
        when(media.findDraftVideoByVersion("task", "default", "version")).thenReturn(Optional.of(draft));
        when(draft.status()).thenReturn("UPLOADED");
        when(jobs.find("task", "default", "run")).thenReturn(Optional.of(new FixedProjectJob("run", "task", "default", "source", project)));
        when(runtime.sourceProbeStage(eq(lease), anyString())).thenReturn(1);
        when(generated.claimProbe(anyString(), anyString(), anyString(), anyString(), nullable(String.class), anyString())).thenReturn(1);
        when(media.updateDraftVideoProbeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                nullable(Long.class), nullable(Integer.class), nullable(Integer.class), nullable(BigDecimal.class),
                nullable(String.class), nullable(String.class), nullable(Boolean.class))).thenReturn(1);
    }

    @Test void uploadedSourceGetsGenerationBoundAttempt() {
        var claim = service.begin(lease, "version");
        assertThat(claim.attemptId()).startsWith("creation-2-").hasSizeLessThanOrEqualTo(64);
        verify(generated).claimProbe("task", "default", "version", "UPLOADED", null, claim.attemptId());
        verify(runtime).sourceProbeStage(lease, "SOURCE_PROBING");
    }

    @Test void readyMetadataIsReusedWithoutAnotherClaim() {
        when(draft.status()).thenReturn("READY_FOR_REVIEW");
        when(draft.durationMs()).thenReturn(2000L); when(draft.width()).thenReturn(1280); when(draft.height()).thenReturn(720);
        when(draft.frameRate()).thenReturn(new BigDecimal("24.000000")); when(draft.videoCodec()).thenReturn("h264");
        when(draft.audioCodec()).thenReturn("aac"); when(draft.hasAudio()).thenReturn(true);
        assertThat(service.begin(lease, "version").attemptId()).isNull();
        verify(generated, never()).claimProbe(anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(runtime).sourceProbeStage(lease, "SOURCE_PROBED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"creation-1-old", "manual-previous"})
    void newLeaseCanSupersedePreviousProbeToken(String token) {
        when(draft.status()).thenReturn("PROBING"); when(draft.probeAttemptId()).thenReturn(token);
        var claim = service.begin(lease, "version");
        verify(generated).claimProbe("task", "default", "version", "PROBING", token, claim.attemptId());
    }

    @Test void sameGenerationDoesNotStartProbeTwice() {
        when(draft.status()).thenReturn("PROBING"); when(draft.probeAttemptId()).thenReturn("creation-2-already-started");
        assertThatThrownBy(() -> service.begin(lease, "version")).isInstanceOf(ResponseStatusException.class);
        verify(generated, never()).claimProbe(anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void anotherVersionCannotBorrowThisRunAuthority() {
        assertThatThrownBy(() -> service.begin(lease, "other-version")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jobs);
        verify(media, never()).findDraftVideoByVersion(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(longs = {1935, 2000, 2065})
    void measuredContainerDurationAllowsOnlyEstablishedSixtyFiveMsTolerance(long duration) {
        assertThat(service.complete(lease, "version", "creation-2-current", measured(duration, 1280, "aac", true))).isSameAs(draft);
        verify(runtime).sourceProbeStage(lease, "SOURCE_PROBED");
    }

    @ParameterizedTest
    @ValueSource(longs = {1934, 2066})
    void sourceOutsideDurationToleranceCannotBecomeReady(long duration) {
        assertThatThrownBy(() -> service.complete(lease, "version", "creation-2-current", measured(duration, 1280, "aac", true)))
                .isInstanceOf(RenderExecutionException.class);
        verify(media, never()).updateDraftVideoProbeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test void wrongPictureSizeCannotBeReportedAsConfirmedSource() {
        assertThatThrownBy(() -> service.complete(lease, "version", "creation-2-current", measured(2000, 1920, "aac", true)))
                .isInstanceOf(RenderExecutionException.class);
    }

    @Test void silentAacTrackRemainsAnActualAudioTrack() {
        service.complete(lease, "version", "creation-2-current", measured(2000, 1280, "aac", true));
        verify(media).updateDraftVideoProbeResult("task", "default", "version", "PROBING", "creation-2-current",
                "READY_FOR_REVIEW", 2000L, 1280, 720, new BigDecimal("24"), "h264", "aac", true);
        assertThatThrownBy(() -> service.complete(lease, "version", "creation-2-current", measured(2000, 1280, null, false)))
                .isInstanceOf(RenderExecutionException.class);
    }

    @Test void finalDatabaseClockFenceIsCheckedAfterMetadataWrite() {
        when(runtime.sourceProbeStage(lease, "SOURCE_PROBED")).thenReturn(0);
        assertThatThrownBy(() -> service.complete(lease, "version", "creation-2-current", measured(2000, 1280, "aac", true)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("租约已失效");
        var order = inOrder(media, runtime);
        order.verify(media).findDraftVideoByVersion("task", "default", "version");
        order.verify(media).updateDraftVideoProbeResult("task", "default", "version", "PROBING", "creation-2-current",
                "READY_FOR_REVIEW", 2000L, 1280, 720, new BigDecimal("24"), "h264", "aac", true);
        order.verify(runtime).sourceProbeStage(lease, "SOURCE_PROBED");
    }

    @Test void oldGenerationCannotMarkCurrentProbeFailed() {
        assertThatThrownBy(() -> service.fail(lease, "version", "creation-1-old")).isInstanceOf(ResponseStatusException.class);
        verify(media, never()).updateDraftVideoProbeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    private MediaProbeResult measured(long duration, int width, String audioCodec, boolean hasAudio) {
        return new MediaProbeResult(duration, width, 720, new BigDecimal("24"), "h264", audioCodec, hasAudio);
    }
}
