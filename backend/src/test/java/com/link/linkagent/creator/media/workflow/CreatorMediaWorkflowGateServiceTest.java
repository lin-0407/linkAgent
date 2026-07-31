package com.link.linkagent.creator.media.workflow;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeRecoveryService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段 7 发布后流程门禁测试。
 * <p>
 * 验证媒体开关和任务级成片状态共同决定是否允许写入发布后数据，避免只检查全局开关而跳过试映。
 */
class CreatorMediaWorkflowGateServiceTest {

    @Test
    void shouldRejectBeforeReadingMediaWhenFeatureIsDisabled() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(false);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "观众反馈"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(mapper, workflowMapper);
    }

    @Test
    void shouldAllowPostPublishWhenPreflightReviewIsCompleted() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        service.ensureReadyForPostPublish("task-1", "default", "观众反馈");
    }

    @Test
    void shouldAllowPostPublishAfterPlanningSkipAndCompletedPreflight() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(mapper.countPlanningSkippedTask("task-1", "default")).thenReturn(1);
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        service.ensureReadyForPostPublish("task-1", "default", "观众反馈");

        verifyNoInteractions(workflowMapper);
    }

    @Test
    void shouldRejectCompletedReviewFromPreviousMediaProcessingJob() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        MediaProcessingMapper processingMapper = completedProcessingMapper();
        PreflightReviewMapper preflightReviewMapper = mock(PreflightReviewMapper.class);
        PreflightReviewRecord review = mock(PreflightReviewRecord.class);
        when(review.status()).thenReturn("COMPLETED");
        when(review.processingJobId()).thenReturn("job-old");
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        when(preflightReviewMapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(review));
        CreatorMediaWorkflowGateService service = new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                processingMapper,
                preflightReviewMapper,
                confirmedWorkflowMapper(),
                confirmedSuggestionMapper(),
                new DraftVideoProbeRecoveryService(properties, mapper)
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "BV绑定"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("最新媒体预处理结果");
    }

    @Test
    void shouldRejectPostPublishWhenPreflightReviewHasNotStarted() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        MediaProcessingMapper processingMapper = completedProcessingMapper();
        PreflightReviewMapper preflightReviewMapper = mock(PreflightReviewMapper.class);
        CreatorWorkflowMapper workflowMapper = confirmedWorkflowMapper();
        CreatorSuggestionMapper suggestionMapper = confirmedSuggestionMapper();
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        when(preflightReviewMapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.empty());
        CreatorMediaWorkflowGateService service = new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                processingMapper,
                preflightReviewMapper,
                workflowMapper,
                suggestionMapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "BV绑定"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("发布前试映");
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "RETRY_WAIT", "FAILED", "CANCEL_REQUESTED", "CANCELLED"})
    void shouldRejectPostPublishWhenPreflightReviewIsNotCompleted(String status) {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        MediaProcessingMapper processingMapper = completedProcessingMapper();
        PreflightReviewMapper preflightReviewMapper = mock(PreflightReviewMapper.class);
        CreatorWorkflowMapper workflowMapper = confirmedWorkflowMapper();
        CreatorSuggestionMapper suggestionMapper = confirmedSuggestionMapper();
        PreflightReviewRecord review = mock(PreflightReviewRecord.class);
        when(review.status()).thenReturn(status);
        when(review.processingJobId()).thenReturn("job-1");
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        when(preflightReviewMapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(review));
        CreatorMediaWorkflowGateService service = new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                processingMapper,
                preflightReviewMapper,
                workflowMapper,
                suggestionMapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "观众反馈"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("发布前试映");
    }

    @Test
    void shouldRejectPostPublishWhenMediaProcessingHasNotStarted() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorSuggestionRecord suggestion = new CreatorSuggestionRecord();
        suggestion.setTaskId("task-1");
        suggestion.setSuggestionId("suggestion-1");
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.of(suggestion));
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("READY_FOR_REVIEW")));
        CreatorMediaWorkflowGateService service = new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                processingMapper,
                mock(PreflightReviewMapper.class),
                workflowMapper,
                suggestionMapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "BV绑定"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("媒体预处理");
    }

    @Test
    void shouldRejectWhenDraftIsStillProbing() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        when(mapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("PROBING")));
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "BV绑定"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("正在媒体探测中");
    }

    @Test
    void shouldRecoverStaleProbeBeforeRejectingPostPublish() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        properties.getProcessing().setProbeTimeout(Duration.ofSeconds(30));
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        DraftVideoRecord staleDraft = draft("PROBING", LocalDateTime.now().minusMinutes(2));
        when(mapper.findDraftVideo("task-1", "default")).thenReturn(Optional.of(staleDraft));
        when(mapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(draft("PROBE_FAILED")));
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "观众反馈"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("尚未通过媒体探测");
        verify(mapper).recoverStaleDraftVideoProbe(
                "task-1", "default", "version-1", any(LocalDateTime.class));
    }

    @Test
    void shouldRejectBeforeReadingDraftWhenPrePublishIsNotConfirmed() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorWorkflowSessionRecord pendingSession = confirmedSession();
        pendingSession.setStatus(CreatorWorkflowStatus.WAITING_CONFIRMATION.name());
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(pendingSession));
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureReadyForPostPublish("task-1", "default", "观众反馈"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("请先确认发布方案");
        verify(mapper).countPlanningSkippedTask("task-1", "default");
        verify(mapper, never()).findDraftVideo("task-1", "default");
    }

    @Test
    void shouldRejectUploadWhenPrePublishSessionIsMissing() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.empty());
        CreatorMediaWorkflowGateService service = service(properties, mapper, workflowMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensurePrePublishConfirmed("task-1", "default", "成片试映"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("请先确认发布方案");
        verify(mapper).countPlanningSkippedTask("task-1", "default");
        verify(mapper, never()).findDraftVideo("task-1", "default");
    }

    private DraftVideoRecord draft(String status) {
        return draft(status, LocalDateTime.now());
    }

    private DraftVideoRecord draft(String status, LocalDateTime updateTime) {
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
                30_000L,
                1920,
                1080,
                null,
                "h264",
                "aac",
                true,
                null,
                status,
                null,
                LocalDateTime.now(),
                updateTime
        );
    }

    private CreatorWorkflowSessionRecord confirmedSession() {
        CreatorWorkflowSessionRecord session = new CreatorWorkflowSessionRecord();
        session.setSessionId("session-1");
        session.setTaskId("task-1");
        session.setUserId("default");
        session.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        session.setStatus(CreatorWorkflowStatus.CONFIRMED.name());
        session.setConfirmedResultId("suggestion-1");
        return session;
    }

    private CreatorMediaWorkflowGateService service(CreatorMediaProperties properties,
                                                     MediaUploadMapper mapper,
                                                     CreatorWorkflowMapper workflowMapper) {
        CreatorSuggestionMapper suggestionMapper = confirmedSuggestionMapper();
        MediaProcessingMapper processingMapper = completedProcessingMapper();
        PreflightReviewMapper preflightReviewMapper = mock(PreflightReviewMapper.class);
        PreflightReviewRecord completedReview = mock(PreflightReviewRecord.class);
        when(completedReview.status()).thenReturn("COMPLETED");
        when(completedReview.processingJobId()).thenReturn("job-1");
        when(preflightReviewMapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(completedReview));
        return new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                processingMapper,
                preflightReviewMapper,
                workflowMapper,
                suggestionMapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );
    }

    private MediaProcessingMapper completedProcessingMapper() {
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        MediaProcessingJobRecord completedJob = mock(MediaProcessingJobRecord.class);
        when(completedJob.status()).thenReturn("COMPLETED");
        when(completedJob.jobId()).thenReturn("job-1");
        when(processingMapper.findCurrentJob("task-1", "default", "version-1"))
                .thenReturn(Optional.of(completedJob));
        return processingMapper;
    }

    private CreatorWorkflowMapper confirmedWorkflowMapper() {
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        return workflowMapper;
    }

    private CreatorSuggestionMapper confirmedSuggestionMapper() {
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorSuggestionRecord suggestion = new CreatorSuggestionRecord();
        suggestion.setTaskId("task-1");
        suggestion.setSuggestionId("suggestion-1");
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.of(suggestion));
        return suggestionMapper;
    }
}
