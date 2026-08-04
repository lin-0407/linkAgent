package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

        String taskId = service.ensureSubmitted(review(), transcribeStep(), null);

        assertThat(taskId).isEqualTo("provider-task-1");
        verifyNoInteractions(storage, provider);
    }

    @Test
    void shouldPersistNoSpeechAsSkippedAndCompleteCallLog() throws Exception {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PreflightAsrService service = new PreflightAsrService(
                new CreatorMediaProperties(),
                mapper,
                mock(ObjectStorageService.class),
                mock(SpeechRecognitionProvider.class),
                objectMapper
        );
        BigDecimal actualCost = new BigDecimal("0.00042000");
        when(mapper.finishStep(eq("review-1"), eq("TRANSCRIBE"), eq("SKIPPED"),
                anyString(), isNull(), isNull())).thenReturn(1);

        service.skipTranscript(
                review(), transcribeStep(), "SUCCESS_WITH_NO_VALID_FRAGMENT", 12L, actualCost
        );

        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).finishStep(eq("review-1"), eq("TRANSCRIBE"), eq("SKIPPED"),
                outputCaptor.capture(), isNull(), isNull());
        var output = objectMapper.readTree(outputCaptor.getValue());
        assertThat(output.path("reason").asText()).isEqualTo("NO_SPEECH_DETECTED");
        assertThat(output.path("providerCode").asText()).isEqualTo("SUCCESS_WITH_NO_VALID_FRAGMENT");
        assertThat(output.path("usageSeconds").asLong()).isEqualTo(12L);
        verify(mapper).completeAsrCall("review-1", "provider-task-1", 12000L, actualCost);
        assertThat(PreflightAsrService.class.getMethod(
                "skipTranscript", PreflightReviewRecord.class, PreflightStepRecord.class,
                String.class, Long.class, BigDecimal.class
        ).isAnnotationPresent(Transactional.class)).isTrue();
    }

    private PreflightReviewRecord review() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "RUNNING", "TRANSCRIBE", 10, 1L, false, 0, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, null, null, "USD",
                null, null, null, null, null, null
        );
    }

    private PreflightStepRecord transcribeStep() {
        return new PreflightStepRecord(
                null, "step-1", "review-1", "TRANSCRIBE", 1, "RUNNING", 1,
                "fingerprint", null, "provider-task-1", null, null, null, null, null, null
        );
    }
}
