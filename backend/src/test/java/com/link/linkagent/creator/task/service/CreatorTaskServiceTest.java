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
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void shouldMarkDraftTaskAsPlanningSkipped() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        CreatorTaskService service = new CreatorTaskService(mapper);

        CreatorTaskResponse response = service.skipToPreflight("task-1");

        assertThat(response.planningSkipped()).isTrue();
        assertThat(mapper.taskRecord.isPlanningSkipped()).isTrue();
    }

    @Test
    void shouldRejectPlanningSkipAfterPrePublishWasConfirmed() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        mapper.taskRecord.setStatus(CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        CreatorTaskService service = new CreatorTaskService(mapper);

        assertThatThrownBy(() -> service.skipToPreflight("task-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(mapper.taskRecord.isPlanningSkipped()).isFalse();
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

    @Test
    void shouldRejectTaskUpdateAfterPrePublishSuggestionHasBeenConfirmed() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        mapper.taskRecord.setStatus(CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        CreatorTaskService service = new CreatorTaskService(mapper);

        // 已确认的发布方案会作为后续制作和竞品分析的事实基线，
        // 不能再通过普通任务保存覆盖视频类型或文稿，否则历史结果会失去对应输入。
        assertThatThrownBy(() -> service.updateTask(
                "task-1",
                new CreatorTaskUpdateRequest("新任务名", "游戏攻略", "新标题", "新简介", "新文稿", null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 拒绝必须发生在任何持久化调用之前，保证已确认任务的原始材料没有被部分覆盖。
        assertThat(mapper.updatedTaskName).isNull();
        assertThat(mapper.updatedVideoType).isNull();
        assertThat(mapper.updatedStatuses).isEmpty();
    }

    @Test
    void shouldRejectMaterialImportAfterPrePublishSuggestionHasBeenConfirmed() {
        FakeCreatorTaskMapper mapper = new FakeCreatorTaskMapper();
        mapper.taskRecord = createTaskRecord();
        mapper.taskRecord.setStatus(CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        CreatorTaskService service = new CreatorTaskService(mapper);

        // 文件导入与表单保存都能修改分析输入，因此必须使用同一份任务锁定规则。
        // 任务已锁定时应在读取文件前失败，避免无意义的文件解析和覆盖风险。
        MultipartFile file = mock(MultipartFile.class);
        assertThatThrownBy(() -> service.importMaterial("task-1", "MANUSCRIPT", file))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(mapper.materialsByType).isEmpty();
        assertThat(mapper.updatedStatuses).isEmpty();
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
        public int markPlanningSkipped(String taskId) {
            if (taskRecord == null
                    || !CreatorTaskStatus.DRAFT.name().equals(taskRecord.getStatus())
                    || taskRecord.isPlanningSkipped()) {
                return 0;
            }
            taskRecord.setPlanningSkipped(true);
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
