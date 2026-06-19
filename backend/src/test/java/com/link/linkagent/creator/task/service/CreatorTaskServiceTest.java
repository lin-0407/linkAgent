package com.link.linkagent.creator.task.service;

import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskResponse;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.creator.task.model.CreatorTaskUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorTaskServiceTest {

    @Test
    void shouldUpdateTaskAndArchiveOldMaterialWhenContentChanges() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        mapper.materials.add(createMaterialRecord("TITLE_DRAFT", "旧标题"));
        mapper.materials.add(createMaterialRecord("DESCRIPTION_DRAFT", "旧简介"));

        CreatorTaskService service = new CreatorTaskService(mapper);

        CreatorTaskResponse response = service.updateTask(
                "task-1",
                new CreatorTaskUpdateRequest("新任务名", "知识科普", "新标题", "新简介", "新文稿", "")
        );

        assertThat(mapper.updatedTaskName).isEqualTo("新任务名");
        assertThat(mapper.updatedVideoType).isEqualTo("知识科普");
        assertThat(mapper.updatedStatuses).contains(CreatorTaskStatus.DRAFT.name());
        assertThat(mapper.deletedMaterialTypes).contains(CreatorMaterialType.SUBTITLE.name());
        assertThat(mapper.materialsByType.get(CreatorMaterialType.TITLE_DRAFT.name()).getContent())
                .isEqualTo("新标题");
        assertThat(response.taskName()).isEqualTo("新任务名");
        assertThat(response.materials()).hasSize(3);
    }

    @Test
    void shouldArchiveTaskAndMaterialsWhenDeleting() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        mapper.materials.add(createMaterialRecord("TITLE_DRAFT", "标题"));

        CreatorTaskService service = new CreatorTaskService(mapper);

        service.deleteTask("task-1");

        assertThat(mapper.deletedTaskStatus).isEqualTo(CreatorTaskStatus.ARCHIVED.name());
        assertThat(mapper.deletedTaskFlag).isTrue();
        assertThat(mapper.materialsDeletedFlag).isTrue();
    }

    @Test
    void shouldRejectMissingTaskWhenUpdating() {
        CreatorTaskService service = new CreatorTaskService(new FakeCreatorTaskMapper());

        assertThatThrownBy(() ->
                service.updateTask(
                        "missing-task",
                        new CreatorTaskUpdateRequest("任务名", "知识科普", "标题", "简介", "文稿", null)
                ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("创作任务不存在");
    }

    private CreatorTaskRecord createTaskRecord() {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setUserId("default");
        record.setTaskName("旧任务名");
        record.setVideoType("未分类");
        record.setStatus(CreatorTaskStatus.DRAFT.name());
        return record;
    }

    private CreatorMaterialRecord createMaterialRecord(String type, String content) {
        CreatorMaterialRecord record = new CreatorMaterialRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setMaterialType(type);
        record.setContent(content);
        return record;
    }

    private static class FakeCreatorTaskMapper implements CreatorTaskMapper {

        private CreatorTaskRecord taskRecord;
        private final List<CreatorMaterialRecord> materials = new ArrayList<>();
        private final List<String> updatedStatuses = new ArrayList<>();
        private final List<String> deletedMaterialTypes = new ArrayList<>();
        private final Map<String, CreatorMaterialRecord> materialsByType = new HashMap<>();
        private String updatedTaskName;
        private String updatedVideoType;
        private String deletedTaskStatus;
        private boolean deletedTaskFlag;
        private boolean materialsDeletedFlag;

        @Override
        public int insertTask(CreatorTaskRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int upsertMaterial(CreatorMaterialRecord record) {
            CreatorMaterialRecord saved = new CreatorMaterialRecord();
            saved.setId(record.getId());
            saved.setTaskId(record.getTaskId());
            saved.setMaterialType(record.getMaterialType());
            saved.setContent(record.getContent());
            materialsByType.put(record.getMaterialType(), saved);
            deletedMaterialTypes.remove(record.getMaterialType());
            return 1;
        }

        @Override
        public int deleteMaterialByType(String taskId, String materialType) {
            deletedMaterialTypes.add(materialType);
            materialsByType.remove(materialType);
            return 1;
        }

        @Override
        public Optional<CreatorTaskRecord> findTaskByTaskId(String taskId) {
            if (deletedTaskFlag || taskRecord == null || !taskRecord.getTaskId().equals(taskId)) {
                return Optional.empty();
            }
            return Optional.of(taskRecord);
        }

        @Override
        public List<CreatorMaterialRecord> listMaterialsByTaskId(String taskId) {
            if (!materialsByType.isEmpty()) {
                return new ArrayList<>(materialsByType.values());
            }
            return materials;
        }

        @Override
        public List<CreatorTaskSummaryRecord> listTasksByUser(String userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CreatorTaskSummaryRecord> listRecentTasks(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskStatus(String taskId, String status) {
            updatedStatuses.add(status);
            if (taskRecord != null) {
                taskRecord.setStatus(status);
            }
            return 1;
        }

        @Override
        public int updateTaskName(String taskId, String taskName) {
            updatedTaskName = taskName;
            if (taskRecord != null) {
                taskRecord.setTaskName(taskName);
            }
            return 1;
        }

        @Override
        public int updateTaskBasicInfo(String taskId, String taskName, String videoType) {
            updatedTaskName = taskName;
            updatedVideoType = videoType;
            if (taskRecord != null) {
                taskRecord.setTaskName(taskName);
                taskRecord.setVideoType(videoType);
            }
            return 1;
        }

        @Override
        public int deleteTask(String taskId, String status) {
            deletedTaskStatus = status;
            deletedTaskFlag = true;
            if (taskRecord != null) {
                taskRecord.setStatus(status);
            }
            return 1;
        }

        @Override
        public int deleteMaterialsByTaskId(String taskId) {
            materialsDeletedFlag = true;
            materialsByType.clear();
            return 1;
        }
    }
}
