package com.link.linkagent.creator.media.probe.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1 成片媒体探测应用服务测试。
 * <p>
 * 这里只验证状态和元信息写入边界；ffprobe 可执行文件与真实 OSS 短签 URL 由部署环境联调验证。
 */
class DraftVideoProbeServiceTest {

    @Test
    void shouldPersistMetadataAndMarkReadyWhenProbeSucceeds() {
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        FfprobeMediaProbeService ffprobeService = mock(FfprobeMediaProbeService.class);
        DraftVideoRecord uploaded = draft("UPLOADED", null, null, null, null, null, null, null);
        DraftVideoRecord ready = draft(
                "READY_FOR_REVIEW",
                30_000L,
                1920,
                1080,
                new BigDecimal("29.970000"),
                "h264",
                "aac",
                true
        );
        when(mapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(uploaded), Optional.of(ready));
        when(storageService.presignGetObject(eq(uploaded.bucketName()), eq(uploaded.objectKey()), any()))
                .thenReturn(new PresignedObjectRead("https://example.invalid/source.mp4", Instant.now().plusSeconds(300)));
        when(ffprobeService.probe("https://example.invalid/source.mp4"))
                .thenReturn(new MediaProbeResult(30_000L, 1920, 1080, new BigDecimal("29.970000"), "h264", "aac", true));
        when(mapper.claimDraftVideoProbe(eq("task-1"), eq("default"), eq("version-1"), anyString()))
                .thenReturn(1);
        when(mapper.updateDraftVideoProbeResult(
                eq("task-1"), eq("default"), eq("version-1"), eq("PROBING"), anyString(),
                eq("READY_FOR_REVIEW"), eq(30_000L), eq(1920), eq(1080),
                eq(new BigDecimal("29.970000")), eq("h264"), eq("aac"), eq(true)
        )).thenReturn(1);

        DraftVideoResponse response = service(mapper, storageService, ffprobeService)
                .probeDraftVideo("default", "task-1", "version-1");

        assertThat(response.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(response.durationMs()).isEqualTo(30_000L);
        assertThat(response.hasAudio()).isTrue();
        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultToken = ArgumentCaptor.forClass(String.class);
        verify(mapper).claimDraftVideoProbe(
                eq("task-1"), eq("default"), eq("version-1"), claimToken.capture());
        verify(mapper).updateDraftVideoProbeResult(
                eq("task-1"), eq("default"), eq("version-1"), eq("PROBING"), resultToken.capture(),
                eq("READY_FOR_REVIEW"), eq(30_000L), eq(1920), eq(1080),
                eq(new BigDecimal("29.970000")), eq("h264"), eq("aac"), eq(true)
        );
        assertThat(resultToken.getValue()).isEqualTo(claimToken.getValue());
    }

    @Test
    void shouldMarkProbeFailedWhenVideoExceedsDurationLimit() {
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        FfprobeMediaProbeService ffprobeService = mock(FfprobeMediaProbeService.class);
        DraftVideoRecord uploaded = draft("UPLOADED", null, null, null, null, null, null, null);
        MediaProbeResult overLimit = new MediaProbeResult(
                1_800_001L,
                1920,
                1080,
                new BigDecimal("30.000000"),
                "h264",
                "aac",
                true
        );
        when(mapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(uploaded));
        when(storageService.presignGetObject(eq(uploaded.bucketName()), eq(uploaded.objectKey()), any()))
                .thenReturn(new PresignedObjectRead("https://example.invalid/source.mp4", Instant.now().plusSeconds(300)));
        when(ffprobeService.probe("https://example.invalid/source.mp4")).thenReturn(overLimit);
        when(mapper.claimDraftVideoProbe(eq("task-1"), eq("default"), eq("version-1"), anyString()))
                .thenReturn(1);
        when(mapper.updateDraftVideoProbeResult(
                eq("task-1"), eq("default"), eq("version-1"), eq("PROBING"), anyString(),
                eq("PROBE_FAILED"), eq(1_800_001L), eq(1920), eq(1080),
                eq(new BigDecimal("30.000000")), eq("h264"), eq("aac"), eq(true)
        )).thenReturn(1);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service(mapper, storageService, ffprobeService)
                        .probeDraftVideo("default", "task-1", "version-1"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(mapper).updateDraftVideoProbeResult(
                eq("task-1"), eq("default"), eq("version-1"), eq("PROBING"), anyString(),
                eq("PROBE_FAILED"), eq(1_800_001L), eq(1920), eq(1080),
                eq(new BigDecimal("30.000000")), eq("h264"), eq("aac"), eq(true)
        );
    }

    @Test
    void shouldRejectLateResultWhenProbeAttemptHasChanged() {
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        FfprobeMediaProbeService ffprobeService = mock(FfprobeMediaProbeService.class);
        DraftVideoRecord uploaded = draft("UPLOADED", null, null, null, null, null, null, null);
        MediaProbeResult result = new MediaProbeResult(
                30_000L, 1920, 1080, new BigDecimal("30.000000"), "h264", "aac", true);
        when(mapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(uploaded));
        when(storageService.presignGetObject(eq(uploaded.bucketName()), eq(uploaded.objectKey()), any()))
                .thenReturn(new PresignedObjectRead("https://example.invalid/source.mp4", Instant.now().plusSeconds(300)));
        when(ffprobeService.probe("https://example.invalid/source.mp4")).thenReturn(result);
        when(mapper.claimDraftVideoProbe(eq("task-1"), eq("default"), eq("version-1"), anyString()))
                .thenReturn(1);
        when(mapper.updateDraftVideoProbeResult(
                eq("task-1"), eq("default"), eq("version-1"), eq("PROBING"), anyString(),
                eq("READY_FOR_REVIEW"), eq(30_000L), eq(1920), eq(1080),
                eq(new BigDecimal("30.000000")), eq("h264"), eq("aac"), eq(true)
        )).thenReturn(0);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service(mapper, storageService, ffprobeService)
                        .probeDraftVideo("default", "task-1", "version-1"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("状态已变化");
    }

    private DraftVideoProbeService service(MediaUploadMapper mapper,
                                           ObjectStorageService storageService,
                                           FfprobeMediaProbeService ffprobeService) {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        return new DraftVideoProbeService(
                properties,
                storageService,
                ffprobeService,
                mapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );
    }

    private DraftVideoRecord draft(String status,
                                   Long durationMs,
                                   Integer width,
                                   Integer height,
                                   BigDecimal frameRate,
                                   String videoCodec,
                                   String audioCodec,
                                   Boolean hasAudio) {
        return new DraftVideoRecord(
                1L,
                "version-1",
                "task-1",
                "default",
                1,
                "V1 初剪",
                "source.mp4",
                "linkagent-private-media",
                "users/default/tasks/task-1/versions/version-1/original/source.mp4",
                "video/mp4",
                1024L,
                durationMs,
                width,
                height,
                frameRate,
                videoCodec,
                audioCodec,
                hasAudio,
                null,
                status,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
