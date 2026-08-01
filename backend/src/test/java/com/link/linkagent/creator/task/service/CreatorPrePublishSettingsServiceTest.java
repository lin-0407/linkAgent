package com.link.linkagent.creator.task.service;

import com.link.linkagent.creator.task.mapper.CreatorPrePublishSettingsMapper;
import com.link.linkagent.creator.task.model.PrePublishSettingsRecord;
import com.link.linkagent.creator.task.model.PrePublishSettingsResponse;
import com.link.linkagent.creator.task.model.PrePublishSettingsUpdateRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorPrePublishSettingsServiceTest {

    @Test
    void shouldReturnDefaultsBeforeTaskSettingsAreSaved() {
        FakeMapper mapper = new FakeMapper();
        CreatorPrePublishSettingsService service = new CreatorPrePublishSettingsService(mapper);

        PrePublishSettingsResponse response = service.getSettings("task-1");

        assertThat(response.preferenceMode()).isEqualTo("USE_HISTORY");
        assertThat(response.creatorPreference()).isEmpty();
        assertThat(response.updateTime()).isNull();
    }

    @Test
    void shouldSaveAllTaskScopedGenerationSettings() {
        FakeMapper mapper = new FakeMapper();
        CreatorPrePublishSettingsService service = new CreatorPrePublishSettingsService(mapper);

        PrePublishSettingsResponse response = service.saveSettings(
                "task-1",
                new PrePublishSettingsUpdateRequest(
                        "EXPERIMENT",
                        "让观众记住恢复流程",
                        "克制且具体",
                        "不要标题党",
                        "以当前任务对话为准"
                )
        );

        assertThat(response.preferenceMode()).isEqualTo("EXPERIMENT");
        assertThat(response.creatorPreference()).isEqualTo("让观众记住恢复流程");
        assertThat(response.titleStyle()).isEqualTo("克制且具体");
        assertThat(response.extraRequirement()).isEqualTo("不要标题党");
        assertThat(response.customGuidance()).isEqualTo("以当前任务对话为准");
    }

    private static class FakeMapper implements CreatorPrePublishSettingsMapper {

        private PrePublishSettingsRecord record;

        @Override
        public int countTask(String taskId) {
            return "task-1".equals(taskId) ? 1 : 0;
        }

        @Override
        public Optional<PrePublishSettingsRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(record);
        }

        @Override
        public int upsert(PrePublishSettingsRecord source) {
            record = source;
            record.setUpdateTime(LocalDateTime.now());
            return 1;
        }
    }
}
