package com.link.linkagent.creator.media.workflow;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    void shouldAllowPostPublishWhenDraftProbeIsReady() {
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
        verifyNoInteractions(mapper);
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
        verifyNoInteractions(mapper);
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
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorSuggestionRecord suggestion = new CreatorSuggestionRecord();
        suggestion.setTaskId("task-1");
        suggestion.setSuggestionId("suggestion-1");
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.of(suggestion));
        return new CreatorMediaWorkflowGateService(
                properties,
                mapper,
                workflowMapper,
                suggestionMapper,
                new DraftVideoProbeRecoveryService(properties, mapper)
        );
    }
}
