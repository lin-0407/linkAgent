package com.link.linkagent.creator.report.service;

import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.model.CreatorReportResponse;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CreatorReportServiceTest {

    @Test
    void shouldGenerateReportWhenPrerequisitesExist() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        suggestionMapper.record = createSuggestionRecord();

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.reportRecord = createCompetitorReportRecord();

        FakeCreatorReportMapper reportMapper = new FakeCreatorReportMapper();
        FakeCreatorPreferenceMapper preferenceMapper = new FakeCreatorPreferenceMapper();
        CreatorReportService service = new CreatorReportService(
                taskMapper,
                suggestionMapper,
                feedbackMapper,
                competitorMapper,
                reportMapper,
                new CreatorPreferenceService(preferenceMapper),
                new FixedLlmService("""
                        {"contentSummary":"本期内容总结","coreSellingPoints":["卖点1"],"titleDescriptionReview":{"titleConclusion":"标题合适","descriptionConclusion":"简介清楚","tagAndPartitionConclusion":"分区准确","riskReminder":"注意风险"},"audienceFeedbackSummary":"反馈不错","competitorComparison":{"benchmarkConclusion":"对标清楚","ownAdvantages":["优势"],"ownDisadvantages":["短板"],"differentiationStrategy":"做差异化"},"controversyAndMisunderstanding":[{"point":"争议点","impact":"中等","action":"继续解释"}],"nextActionSuggestions":[{"suggestion":"做下一期","reason":"观众想看","priority":"HIGH"}],"creatorPreferenceInsight":["偏好干货表达"],"overallConclusion":"适合继续做"}
                        """),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class));

        CreatorReportResponse response = service.analyze(
                "task-1",
                new CreatorReportAnalyzeRequest("请重点看复盘重点", "关注下一期方向", "尽量简短")
        );

        assertThat(response.reportId()).isEqualTo(reportMapper.savedRecord.getReportId());
        assertThat(response.contentSummary()).isEqualTo("本期内容总结");
        assertThat(response.competitorComparison()).contains("benchmarkConclusion");
        assertThat(response.parseStatus()).isEqualTo("PARSED");
        assertThat(taskMapper.updatedStatus).isEqualTo(CreatorTaskStatus.ANALYZED.name());
        assertThat(reportMapper.savedRecord.getTaskId()).isEqualTo("task-1");
        assertThat(preferenceMapper.savedRecord).isNotNull();
        assertThat(preferenceMapper.savedRecord.getUserId()).isEqualTo("default");
        assertThat(preferenceMapper.savedRecord.getSourceTaskId()).isEqualTo("task-1");
        assertThat(preferenceMapper.savedRecord.getPreferenceContent()).contains("偏好干货表达");
    }

    @Test
    void shouldFailWhenPublishSuggestionMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        CreatorReportService service = new CreatorReportService(
                taskMapper,
                suggestionMapper,
                feedbackMapper,
                new FakeCreatorCompetitorMapper(),
                new FakeCreatorReportMapper(),
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先生成发布前优化建议");
    }

    @Test
    void shouldFailWhenCompetitorReportMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        suggestionMapper.record = createSuggestionRecord();

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        CreatorReportService service = new CreatorReportService(
                taskMapper,
                suggestionMapper,
                feedbackMapper,
                new FakeCreatorCompetitorMapper(),
                new FakeCreatorReportMapper(),
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先完成同类型视频竞品分析");
    }

    @Test
    void shouldKeepRawOutputWhenJsonParsingFails() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        suggestionMapper.record = createSuggestionRecord();

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.reportRecord = createCompetitorReportRecord();

        FakeCreatorReportMapper reportMapper = new FakeCreatorReportMapper();
        FakeCreatorPreferenceMapper preferenceMapper = new FakeCreatorPreferenceMapper();
        CreatorReportService service = new CreatorReportService(
                taskMapper,
                suggestionMapper,
                feedbackMapper,
                competitorMapper,
                reportMapper,
                new CreatorPreferenceService(preferenceMapper),
                new FixedLlmService("不是 JSON"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class));

        CreatorReportResponse response = service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));

        assertThat(response.parseStatus()).isEqualTo("RAW_ONLY");
        assertThat(response.rawOutput()).isEqualTo("不是 JSON");
        assertThat(preferenceMapper.savedRecord).isNull();
    }

    @Test
    void shouldRejectReportGenerationBeforeReadingPrerequisitesWhenMediaGateRejects() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        FakeCreatorReportMapper reportMapper = new FakeCreatorReportMapper();
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "成片尚未通过媒体探测"))
                .when(mediaWorkflowGateService)
                .ensureReadyForPostPublish("task-1", "default", "创作复盘");
        CreatorReportService service = new CreatorReportService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                new FakeCreatorCompetitorMapper(),
                reportMapper,
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mediaWorkflowGateService
        );

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mediaWorkflowGateService).ensureMediaEnabled("创作复盘");
        assertThat(reportMapper.savedRecord).isNull();
        assertThat(taskMapper.updatedStatus).isNull();
    }

    private CreatorTaskRecord createTaskRecord() {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setUserId("default");
        record.setTaskName("复盘任务");
        record.setStatus(CreatorTaskStatus.FEEDBACK_ANALYZED.name());
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
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

    private CreatorSuggestionRecord createSuggestionRecord() {
        CreatorSuggestionRecord record = new CreatorSuggestionRecord();
        record.setId(1L);
        record.setSuggestionId("suggestion-1");
        record.setTaskId("task-1");
        record.setContentSummary("摘要");
        record.setAudienceProfile("受众");
        record.setSellingPoints("[\"卖点1\"]");
        record.setRiskPoints("[\"风险1\"]");
        record.setTitleSuggestions("[{\"title\":\"标题1\"}]");
        record.setDescriptionSuggestion("简介");
        record.setTagSuggestions("[\"标签1\"]");
        record.setPartitionSuggestion("知识区");
        record.setRawOutput("{}");
        record.setParseStatus("PARSED");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorFeedbackReportRecord createFeedbackReportRecord() {
        CreatorFeedbackReportRecord record = new CreatorFeedbackReportRecord();
        record.setId(1L);
        record.setReportId("feedback-report-1");
        record.setTaskId("task-1");
        record.setFeedbackSummary("反馈摘要");
        record.setHotTopics("[{\"topic\":\"高频观点\"}]");
        record.setSentimentSummary("整体偏正向");
        record.setControversyPoints("[{\"point\":\"争议点\"}]");
        record.setMisunderstandingPoints("[{\"point\":\"误解点\"}]");
        record.setNextContentSuggestions("[\"下一期建议\"]");
        record.setInteractionSuggestions("[\"互动建议\"]");
        record.setRawOutput("{}");
        record.setParseStatus("PARSED");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorCompetitorReportRecord createCompetitorReportRecord() {
        CreatorCompetitorReportRecord record = new CreatorCompetitorReportRecord();
        record.setId(1L);
        record.setReportId("competitor-report-1");
        record.setTaskId("task-1");
        record.setCompetitorSummary("竞品整体打法");
        record.setCompetitorAdvantages("[{\"advantage\":\"竞品节奏更快\"}]");
        record.setOwnAdvantages("[{\"advantage\":\"解释更清楚\"}]");
        record.setOwnDisadvantages("[{\"disadvantage\":\"标题不够强\"}]");
        record.setGapAnalysis("[{\"dimension\":\"标题\",\"gap\":\"吸引力不足\",\"priority\":\"HIGH\"}]");
        record.setImprovementSuggestions("[{\"suggestion\":\"优化标题\"}]");
        record.setDifferentiationStrategy("主打清晰拆解");
        record.setRawOutput("{}");
        record.setParseStatus("PARSED");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private static class FixedLlmService extends LLMService {

        private final String response;

        FixedLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            return response;
        }
    }

    private static class FakeCreatorTaskMapper implements CreatorTaskMapper {

        private CreatorTaskRecord taskRecord;
        private List<CreatorMaterialRecord> materials = List.of();
        private String updatedStatus;

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
        public List<com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord> listTasksByUser(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord> listRecentTasks(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskStatus(String taskId, String status) {
            this.updatedStatus = status;
            return 1;
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

        private CreatorSuggestionRecord record;

        @Override
        public int upsert(CreatorSuggestionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(record);
        }

        @Override
        public Optional<CreatorSuggestionRecord> findBySuggestionId(String suggestionId) {
            return Optional.ofNullable(record);
        }
    }

    private static class FakeCreatorFeedbackMapper implements CreatorFeedbackMapper {

        private com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord feedbackRecord;
        private CreatorFeedbackReportRecord reportRecord;

        @Override
        public int upsertFeedback(com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord> findFeedbackByTaskId(String taskId) {
            return Optional.ofNullable(feedbackRecord);
        }

        @Override
        public int upsertReport(CreatorFeedbackReportRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorFeedbackReportRecord> findReportByTaskId(String taskId) {
            return Optional.ofNullable(reportRecord);
        }

        @Override
        public int softDeleteItemsByTaskId(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int softDeleteMetricByTaskId(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int insertItem(com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int upsertMetric(com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord> listItemsByTaskId(
                String taskId,
                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord> listTopCommentItemsByTaskId(
                String taskId,
                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countItemsBySourceType(String taskId, String sourceType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countNoiseItems(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord> countCategoryStats(
                String taskId,
                String sourceType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord> countSentimentStats(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.link.linkagent.creator.feedback.model.CreatorFeedbackDashboardStatRecord> listDashboardStats(
                String taskId) {
            return List.of();
        }

        @Override
        public Optional<com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord> findMetricByTaskId(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CreatorFeedbackItemRecord> listIndexableItemsByTaskId(String taskId, int limit, boolean includeNoise) {
            return List.of();
        }

        @Override
        public int updateItemEmbeddingIndexed(String taskId, String itemId, String embeddingId) {
            return 0;
        }

        @Override
        public int updateItemEmbeddingFailed(String taskId, String itemId, String errorMessage) {
            return 0;
        }

        @Override
        public List<CreatorFeedbackStatRecord> countEmbeddingStatusByTaskId(String taskId) {
            return List.of();
        }

        @Override
        public LocalDateTime findLastEmbeddingUpdateTime(String taskId) {
            return null;
        }

        @Override
        public List<CreatorFeedbackItemRecord> listItemsByTaskIdAndItemIds(String taskId, List<String> itemIds) {
            return List.of();
        }
    }

    private static class FakeCreatorCompetitorMapper implements CreatorCompetitorMapper {

        private CreatorCompetitorSampleRecord competitorVideoRecord;
        private CreatorCompetitorReportRecord reportRecord;

        @Override
        public int upsertCompetitorVideo(CreatorCompetitorSampleRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorCompetitorSampleRecord> findCompetitorVideoByTaskId(String taskId) {
            return Optional.ofNullable(competitorVideoRecord);
        }

        @Override
        public int upsertReport(CreatorCompetitorReportRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorCompetitorReportRecord> findReportByTaskId(String taskId) {
            return Optional.ofNullable(reportRecord);
        }
    }

    private static class FakeCreatorReportMapper implements CreatorReportMapper {

        private CreatorReportRecord savedRecord;

        @Override
        public int upsert(CreatorReportRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<CreatorReportRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(savedRecord);
        }
    }

    private static class FakeCreatorPreferenceMapper implements CreatorPreferenceMapper {

        private CreatorPreferenceRecord savedRecord;
        private List<CreatorPreferenceRecord> records = List.of();

        @Override
        public int upsert(CreatorPreferenceRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public int upsertAdoptionFeedback(CreatorPreferenceRecord record) {
            return 1;
        }

        @Override
        public List<CreatorPreferenceRecord> listByUserId(String userId, int limit) {
            return records.stream().limit(limit).toList();
        }

        @Override
        public List<CreatorPreferenceRecord> listAdoptionFeedbackByUserId(String userId, int limit) {
            return List.of();
        }
    }
}
