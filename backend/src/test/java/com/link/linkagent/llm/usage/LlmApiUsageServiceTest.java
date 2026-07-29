package com.link.linkagent.llm.usage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmApiUsageServiceTest {

    @Test
    void shouldListGlobalCallsWithNormalizedFiltersAndSummary() {
        LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
        LlmApiUsageService service = new LlmApiUsageService(mapper);
        LocalDateTime startTime = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 29, 23, 59);
        LlmApiCallLogSummary summary = new LlmApiCallLogSummary();
        summary.setCallCount(3);
        summary.setSuccessCount(2);
        summary.setFailedCount(1);
        summary.setTotalTokens(1200L);
        summary.setTotalElapsedMs(9000L);
        LlmApiCallRecord record = new LlmApiCallRecord();
        record.setCallId("call-1");

        when(mapper.summarizeCalls(startTime, endTime, "qwen", "发布前优化", "TEXT", "SUCCESS"))
                .thenReturn(summary);
        when(mapper.listCalls(startTime, endTime, "qwen", "发布前优化", "TEXT", "SUCCESS", 25, 25))
                .thenReturn(List.of(record));

        LlmApiCallLogPageResponse response = service.listCalls(
                startTime,
                endTime,
                "  qwen  ",
                "  发布前优化  ",
                "text",
                "success",
                2,
                25
        );

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(25);
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.summary().averageElapsedMs()).isEqualTo(3000L);
        assertThat(response.items()).containsExactly(record);
        verify(mapper).summarizeCalls(startTime, endTime, "qwen", "发布前优化", "TEXT", "SUCCESS");
        verify(mapper).listCalls(startTime, endTime, "qwen", "发布前优化", "TEXT", "SUCCESS", 25, 25);
    }

    @Test
    void shouldRejectReversedTimeRange() {
        LlmApiUsageService service = new LlmApiUsageService(mock(LlmApiUsageMapper.class));
        LocalDateTime startTime = LocalDateTime.of(2026, 7, 30, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 29, 0, 0);

        assertThatThrownBy(() -> service.listCalls(
                startTime,
                endTime,
                null,
                null,
                null,
                null,
                1,
                20
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("开始时间不能晚于结束时间");
    }
}
