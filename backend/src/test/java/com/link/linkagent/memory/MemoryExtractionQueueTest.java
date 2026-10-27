package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryExtractionQueueTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> lists = mock(ListOperations.class);

    @Test
    void shouldTakeOnlyCurrentUsersTasksWithinCapturedBoundary() throws Exception {
        when(redis.opsForList()).thenReturn(lists);
        var old = MemoryExtractionTask.forMessageRange("owner", "old", 0, 12, "now");
        var future = MemoryExtractionTask.forMessageRange("owner", "later", 12, 14, "now");
        var other = MemoryExtractionTask.forMessageRange("other", "old", 0, 12, "now");
        String oldJson = json.writeValueAsString(old), futureJson = json.writeValueAsString(future), otherJson = json.writeValueAsString(other);
        when(lists.range(MemoryExtractionQueue.QUEUE_KEY, 0, -1)).thenReturn(List.of(oldJson, futureJson, otherJson));
        when(lists.remove(anyString(), eq(1L), anyString())).thenReturn(1L);

        assertThat(new MemoryExtractionQueue(redis, json).takePendingForUser("owner", 12)).containsExactly(old);
        verify(lists, never()).remove(anyString(), anyLong(), eq(futureJson));
        verify(lists, never()).remove(anyString(), anyLong(), eq(otherJson));
    }

    @Test
    void shouldValidatePayloadsBeforeRemovingAnyTask() throws Exception {
        when(redis.opsForList()).thenReturn(lists);
        var task = MemoryExtractionTask.forMessageRange("owner", "old", 0, 12, "now");
        when(lists.range(MemoryExtractionQueue.QUEUE_KEY, 0, -1)).thenReturn(List.of(json.writeValueAsString(task), "not-json"));

        assertThatThrownBy(() -> new MemoryExtractionQueue(redis, json).takePendingForUser("owner", 12))
                .isInstanceOf(IllegalStateException.class);
        verify(lists, never()).remove(anyString(), anyLong(), anyString());
    }

    @Test
    void shouldRestoreAlreadyRemovedTaskWhenRedisFailsMidway() throws Exception {
        when(redis.opsForList()).thenReturn(lists);
        var first = MemoryExtractionTask.forMessageRange("owner", "old", 0, 10, "now");
        var second = MemoryExtractionTask.forMessageRange("owner", "old", 10, 12, "now");
        String firstJson = json.writeValueAsString(first), secondJson = json.writeValueAsString(second);
        when(lists.range(MemoryExtractionQueue.QUEUE_KEY, 0, -1)).thenReturn(List.of(firstJson, secondJson));
        when(lists.remove(anyString(), eq(1L), eq(firstJson))).thenReturn(1L);
        when(lists.remove(anyString(), eq(1L), eq(secondJson))).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> new MemoryExtractionQueue(redis, json).takePendingForUser("owner", 12))
                .hasMessage("redis unavailable");
        verify(lists).rightPush(MemoryExtractionQueue.QUEUE_KEY, firstJson);
    }
}
