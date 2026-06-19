package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PrePublishSuggestionServiceTest {

    @Test
    void shouldIncludeHistoricalPreferenceWhenModeUsesHistory() {
        TrackingCreatorPreferenceMapper preferenceMapper = new TrackingCreatorPreferenceMapper();
        preferenceMapper.records = List.of(createPreferenceRecord());

        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        CapturingLlmService llmService = new CapturingLlmService("{\"contentSummary\":\"摘要\"}");

        PrePublishSuggestionService service = new PrePublishSuggestionService(
                taskMapper,
                suggestionMapper,
                new CreatorPreferenceService(preferenceMapper),
                emptyContextService(),
                llmService,
                new ObjectMapper(),
                new StubPromptService());

        CreatorSuggestionResponse response = service.generateSuggestion(
                "task-1",
                new PrePublishAnalyzeRequest(null, null, null, null, "USE_HISTORY")
        );

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(preferenceMapper.callCount).isEqualTo(1);
        assertThat(llmService.lastUserMessage).contains("沿用历史偏好");
        assertThat(llmService.lastUserMessage).contains("偏好干货表达");
    }

    @Test
    void shouldSkipHistoricalPreferenceWhenModeIgnoresHistory() {
        TrackingCreatorPreferenceMapper preferenceMapper = new TrackingCreatorPreferenceMapper();
        preferenceMapper.records = List.of(createPreferenceRecord());

        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        CapturingLlmService llmService = new CapturingLlmService("{\"contentSummary\":\"摘要\"}");

        PrePublishSuggestionService service = new PrePublishSuggestionService(
                taskMapper,
                suggestionMapper,
                new CreatorPreferenceService(preferenceMapper),
                emptyContextService(),
                llmService,
                new ObjectMapper(),
                new StubPromptService());

        CreatorSuggestionResponse response = service.generateSuggestion(
                "task-1",
                new PrePublishAnalyzeRequest(null, null, null, null, "IGNORE_HISTORY")
        );

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(preferenceMapper.callCount).isZero();
        assertThat(llmService.lastUserMessage).contains("本期换风格，不使用历史偏好");
    }

    @Test
    void shouldParseCreatorContextFields() {
        TrackingCreatorPreferenceMapper preferenceMapper = new TrackingCreatorPreferenceMapper();

        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        CapturingLlmService llmService = new CapturingLlmService("""
                {
                  "contentSummary": "摘要",
                  "creatorDilemma": "创作者困境",
                  "audienceProfile": "目标受众",
                  "audienceHook": "观众钩子",
                  "contentPositioning": "内容定位",
                  "sellingPoints": ["卖点"],
                  "riskPoints": ["风险"],
                  "titleSuggestions": [
                    {
                      "title": "标题",
                      "viewerPsychology": "观众心理",
                      "clickReason": "点击理由",
                      "trustRisk": "信任风险",
                      "bestScenario": "适用场景",
                      "reason": "理由",
                      "risk": "风险"
                    }
                  ],
                  "descriptionSuggestion": "简介建议",
                  "actionableRevisionPlan": [
                    {
                      "priority": "HIGH",
                      "target": "开头",
                      "problem": "问题",
                      "action": "动作",
                      "expectedEffect": "效果"
                    }
                  ],
                  "tagSuggestions": ["标签"],
                  "partitionSuggestion": "分区"
                }
                """);

        PrePublishSuggestionService service = new PrePublishSuggestionService(
                taskMapper,
                suggestionMapper,
                new CreatorPreferenceService(preferenceMapper),
                emptyContextService(),
                llmService,
                new ObjectMapper(),
                new StubPromptService());

        CreatorSuggestionResponse response = service.generateSuggestion(
                "task-1",
                new PrePublishAnalyzeRequest(null, null, null, null, "IGNORE_HISTORY")
        );

        assertThat(response.creatorDilemma()).isEqualTo("创作者困境");
        assertThat(response.audienceHook()).isEqualTo("观众钩子");
        assertThat(response.contentPositioning()).isEqualTo("内容定位");
        assertThat(response.actionableRevisionPlan()).contains("\"target\":\"开头\"");
        assertThat(response.titleSuggestions()).contains("\"viewerPsychology\":\"观众心理\"");
        assertThat(llmService.lastSystemPrompt).contains("pre_publish.system");
    }

    private CreatorTaskRecord createTaskRecord() {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setUserId("default");
        record.setTaskName("发布前优化任务");
        record.setVideoType("知识科普");
        record.setStatus(CreatorTaskStatus.DRAFT.name());
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorContextService emptyContextService() {
        return new CreatorContextService(null) {
            @Override
            public String buildPromptContext(String userId, String videoType, String scene) {
                return "当前视频类型【" + videoType + "】暂无已沉淀语境。";
            }
        };
    }

    private CreatorMaterialRecord createMaterialRecord() {
        CreatorMaterialRecord record = new CreatorMaterialRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setMaterialType("MANUSCRIPT");
        record.setContent("这里是文稿");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorPreferenceRecord createPreferenceRecord() {
        CreatorPreferenceRecord record = new CreatorPreferenceRecord();
        record.setId(1L);
        record.setPreferenceId("preference-1");
        record.setUserId("default");
        record.setSourceTaskId("task-old");
        record.setSourceReportId("report-old");
        record.setPreferenceContent("[\"偏好干货表达\"]");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private static class CapturingLlmService extends LLMService {

        private final String response;
        private String lastSystemPrompt = "";
        private String lastUserMessage = "";

        CapturingLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserMessage = userMessage;
            return response;
        }
    }

    private static class FakeCreatorTaskMapper implements CreatorTaskMapper {

        private CreatorTaskRecord taskRecord;
        private List<CreatorMaterialRecord> materials = List.of();

        @Override
        public int insertTask(CreatorTaskRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int upsertMaterial(CreatorMaterialRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteMaterialByType(String taskId, String materialType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorTaskRecord> findTaskByTaskId(String taskId) {
            return Optional.ofNullable(taskRecord);
        }

        @Override
        public List<CreatorMaterialRecord> listMaterialsByTaskId(String taskId) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskName(String taskId, String taskName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskBasicInfo(String taskId, String taskName, String videoType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteTask(String taskId, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteMaterialsByTaskId(String taskId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeCreatorSuggestionMapper implements CreatorSuggestionMapper {

        private CreatorSuggestionRecord savedRecord;

        @Override
        public int upsert(CreatorSuggestionRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(savedRecord);
        }

        @Override
        public Optional<CreatorSuggestionRecord> findBySuggestionId(String suggestionId) {
            return Optional.ofNullable(savedRecord);
        }
    }

    private static class TrackingCreatorPreferenceMapper implements CreatorPreferenceMapper {

        private List<CreatorPreferenceRecord> records = List.of();
        private int callCount;

        @Override
        public int upsert(CreatorPreferenceRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CreatorPreferenceRecord> listByUserId(String userId, int limit) {
            callCount++;
            return records.stream().limit(limit).toList();
        }
    }
}
