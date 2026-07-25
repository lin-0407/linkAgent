package com.link.linkagent.creator.media.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 P0-2 重做后旧 P0-3 不会继续作为当前试映。 */
class MediaProcessingServiceTest {

    @Test
    void shouldClearCurrentReviewWhenCreatingJobWithDifferentOptions() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        MediaUploadMapper uploadMapper = mock(MediaUploadMapper.class);
        MediaProcessingJobRecord previous = mock(MediaProcessingJobRecord.class);
        when(previous.status()).thenReturn("COMPLETED");
        when(previous.frameIntervalSeconds()).thenReturn(10);
        when(processingMapper.lockDraftVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of("version-1"));
        when(uploadMapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(readyDraft()));
        when(processingMapper.findCurrentJob("task-1", "default", "version-1"))
                .thenReturn(Optional.of(previous));
        AtomicReference<MediaProcessingJobRecord> inserted = new AtomicReference<>();
        when(processingMapper.insertJob(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(processingMapper.clearCurrentReview("task-1", "default", "version-1")).thenReturn(1);
        when(processingMapper.insertStep(any())).thenReturn(1);
        when(processingMapper.findJob(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
        when(processingMapper.listSteps(any())).thenReturn(List.of());
        when(processingMapper.listAssets(any())).thenReturn(List.of());
        MediaProcessingService service = new MediaProcessingService(
                properties,
                new MediaProcessingCostEstimator(properties),
                processingMapper,
                uploadMapper,
                mock(ObjectStorageService.class),
                new ObjectMapper()
        );

        var response = service.createJob(
                "default",
                "task-1",
                "version-1",
                new MediaProcessingOptionsRequest(
                        5,
                        MediaProcessingOptionsRequest.Resolution.P720,
                        MediaProcessingOptionsRequest.ModelPlan.FLASH,
                        true
                )
        );

        assertThat(response.jobId()).isEqualTo(inserted.get().jobId());
        verify(processingMapper).clearCurrentReview("task-1", "default", "version-1");
    }

    private DraftVideoRecord readyDraft() {
        return new DraftVideoRecord(
                1L, "version-1", "task-1", "default", 1, "V1 初剪", "source.mp4",
                "linkagent-private-media", "users/default/tasks/task-1/versions/version-1/original/source.mp4",
                "video/mp4", 1024L, 30_000L, 1920, 1080, null, "h264", "aac", true,
                null, "READY_FOR_REVIEW", LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
