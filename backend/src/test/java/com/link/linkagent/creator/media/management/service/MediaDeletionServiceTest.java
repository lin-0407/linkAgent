package com.link.linkagent.creator.media.management.service;

import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaDeletionServiceTest {

    private final MediaUploadMapper uploadMapper = mock(MediaUploadMapper.class);
    private final MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
    private final PreflightReviewMapper preflightMapper = mock(PreflightReviewMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final MediaDeletionService service = new MediaDeletionService(
            uploadMapper, processingMapper, preflightMapper, storage);

    @Test
    void shouldDeleteStoredObjectsBeforeMarkingMediaUnavailable() {
        DraftVideoRecord draft = draft(null);
        MediaUploadRecord upload = completedUpload();
        MediaProcessingAssetRecord asset = previewAsset();
        stubDraft(draft);
        when(uploadMapper.listUploadsByVersion("task-1", "default", "version-1"))
                .thenReturn(List.of(upload));
        when(processingMapper.listAssetsByVersion("version-1")).thenReturn(List.of(asset));
        when(uploadMapper.markDraftMediaDeleted("task-1", "default", "version-1")).thenReturn(1);

        service.deleteMedia("default", "task-1", "version-1");

        verify(storage).deleteObject("media-bucket", "derived/preview.mp4");
        verify(storage).deleteObject("media-bucket", "original/source.mp4");
        verify(processingMapper).markAssetsDeleted("version-1");
        verify(preflightMapper).markVersionEvidenceUnavailable("version-1");
        verify(uploadMapper).deleteParts("upload-1");
        verify(uploadMapper).markUploadsDeleted("task-1", "default", "version-1");
        verify(uploadMapper).markDraftMediaDeleted("task-1", "default", "version-1");
    }

    @Test
    void shouldRejectDeletionWhileProcessingIsActive() {
        DraftVideoRecord draft = draft(null);
        MediaProcessingJobRecord job = mock(MediaProcessingJobRecord.class);
        stubDraft(draft);
        when(job.status()).thenReturn("RUNNING");
        when(processingMapper.findCurrentJob("task-1", "default", "version-1"))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.deleteMedia("default", "task-1", "version-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(exception.getReason()).contains("媒体处理");
                });

        verify(storage, never()).deleteObject("media-bucket", "original/source.mp4");
        verify(uploadMapper, never()).markDraftMediaDeleted("task-1", "default", "version-1");
    }

    @Test
    void shouldKeepDatabaseAvailableWhenObjectDeletionFails() {
        DraftVideoRecord draft = draft(null);
        stubDraft(draft);
        when(uploadMapper.listUploadsByVersion("task-1", "default", "version-1"))
                .thenReturn(List.of(completedUpload()));
        when(processingMapper.listAssetsByVersion("version-1")).thenReturn(List.of());
        doThrow(new MediaStorageException("删除媒体对象失败", new IllegalStateException("storage")))
                .when(storage).deleteObject("media-bucket", "original/source.mp4");

        assertThatThrownBy(() -> service.deleteMedia("default", "task-1", "version-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value()));

        verify(uploadMapper, never()).markUploadsDeleted("task-1", "default", "version-1");
        verify(uploadMapper, never()).markDraftMediaDeleted("task-1", "default", "version-1");
    }

    private void stubDraft(DraftVideoRecord draft) {
        when(processingMapper.lockDraftVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of("version-1"));
        when(uploadMapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(draft));
        when(uploadMapper.findCurrentUpload("task-1", "default")).thenReturn(Optional.empty());
        when(processingMapper.findCurrentJob("task-1", "default", "version-1"))
                .thenReturn(Optional.empty());
        when(preflightMapper.findActiveByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.empty());
    }

    private DraftVideoRecord draft(LocalDateTime mediaDeletedAt) {
        LocalDateTime now = LocalDateTime.now();
        return new DraftVideoRecord(
                1L, "version-1", "task-1", "default", 1, "V1", "source.mp4",
                "media-bucket", "original/source.mp4", "video/mp4", 1024L,
                30_000L, 1920, 1080, null, "h264", "aac", true, null,
                "READY_FOR_REVIEW", mediaDeletedAt, now, now
        );
    }

    private MediaUploadRecord completedUpload() {
        LocalDateTime now = LocalDateTime.now();
        return new MediaUploadRecord(
                1L, "upload-1", "version-1", "task-1", "default", "storage-upload-1",
                "original/source.mp4", "video/mp4", 1024L, "fingerprint", 16, 1,
                "COMPLETED", "idempotency-key", null, now.plusHours(1), now, now, now
        );
    }

    private MediaProcessingAssetRecord previewAsset() {
        LocalDateTime now = LocalDateTime.now();
        return new MediaProcessingAssetRecord(
                1L, "asset-1", "job-1", "version-1", "PREVIEW_VIDEO", "media-bucket",
                "derived/preview.mp4", "video/mp4", 512L, null, null,
                1280, 720, 30_000L, now, now
        );
    }
}
