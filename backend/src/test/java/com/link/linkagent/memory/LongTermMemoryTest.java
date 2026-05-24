package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryTest {

    @Test
    void shouldTrimAndSaveMemory() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save(" user-1 ", " user.preference.language ", " Java ", " session-1 ");

        assertThat(mapper.savedRecord.getUserId()).isEqualTo("user-1");
        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.preference.language");
        assertThat(mapper.savedRecord.getContent()).isEqualTo("Java");
        assertThat(mapper.savedRecord.getSourceSessionId()).isEqualTo("session-1");
    }

    @Test
    void shouldNormalizeEmptySourceSessionId() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "key", "content", " ");

        assertThat(mapper.savedRecord.getSourceSessionId()).isNull();
    }

    @Test
    void shouldUseDefaultLimitWhenLimitIsInvalid() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.listByUser(" user-1 ", 0);

        assertThat(mapper.listUserId).isEqualTo("user-1");
        assertThat(mapper.listLimit).isEqualTo(20);
    }

    @Test
    void shouldCapLimitAtMaxValue() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.listByUser("user-1", 101);

        assertThat(mapper.listLimit).isEqualTo(100);
    }

    private static class FakeLongTermMemoryMapper implements LongTermMemoryMapper {

        private LongTermMemoryRecord savedRecord;
        private String listUserId;
        private int listLimit;

        @Override
        public int upsert(LongTermMemoryRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<LongTermMemoryRecord> findByKey(String userId, String memoryKey) {
            return Optional.empty();
        }

        @Override
        public List<LongTermMemoryRecord> listByUser(String userId, int limit) {
            this.listUserId = userId;
            this.listLimit = limit;
            return new ArrayList<>();
        }
    }
}
