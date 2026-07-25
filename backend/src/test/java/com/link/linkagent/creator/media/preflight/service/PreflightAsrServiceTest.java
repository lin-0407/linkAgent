package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证服务恢复后只能复用已落库 Provider ID，不能再次提交 ASR。 */
class PreflightAsrServiceTest {

    @Test
    void shouldReusePersistedProviderTaskIdWithoutSubmittingAgain() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        SpeechRecognitionProvider provider = mock(SpeechRecognitionProvider.class);
        PreflightAsrService service = new PreflightAsrService(
                new CreatorMediaProperties(),
                mock(PreflightReviewMapper.class),
                storage,
                provider,
                new ObjectMapper()
        );
        PreflightReviewRecord review = new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "RUNNING", "TRANSCRIBE", 10, 1L, false, 0, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, null, null, "USD",
                null, null, null, null, null, null
        );
        PreflightStepRecord step = new PreflightStepRecord(
                null, "step-1", "review-1", "TRANSCRIBE", 1, "RUNNING", 1,
                "fingerprint", null, "provider-task-1", null, null, null, null, null, null
        );

        String taskId = service.ensureSubmitted(review, step, null);

        assertThat(taskId).isEqualTo("provider-task-1");
        verifyNoInteractions(storage, provider);
    }
}
