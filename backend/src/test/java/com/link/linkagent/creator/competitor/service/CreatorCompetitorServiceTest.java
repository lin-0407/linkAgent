package com.link.linkagent.creator.competitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalyzeRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSaveRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleResponse;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreatorCompetitorServiceTest {

    private static final String COMPLETE_COMPETITOR_OUTPUT = """
            {"competitorSummary":"竞品更强调结果","competitorAdvantages":[{"advantage":"标题更直接","evidence":"样例标题都突出收益","lesson":"标题前置结果"}],"ownAdvantages":[{"advantage":"解释更细","evidence":"文稿结构完整"}],"ownDisadvantages":[{"disadvantage":"卖点不够前置","evidence":"标题偏平","risk":"点击弱"}],"gapAnalysis":[{"dimension":"标题","gap":"结果感弱","priority":"HIGH"}],"improvementSuggestions":[{"suggestion":"标题突出收益","reason":"竞品样例有效","action":"重写标题"}],"differentiationStrategy":"主打可复制步骤"}
            """;

    @Test
    void shouldSaveCompetitorVideo() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.taskRecord.setStatus(CreatorTaskStatus.ANALYZED.name());

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                competitorMapper,
                invalidationMapper,
                null,
                new FixedLlmService("{}"),
                new ObjectMapper(),
                new StubPromptService());

        CreatorCompetitorSampleResponse response = service.saveCompetitorVideo(
                "task-1",
                new CreatorCompetitorSaveRequest(
                        "BV1xK4y1a7Bc",
                        "竞品视频A",
                        "AI 工具教程",
                        "竞品A：标题突出收益，评论说节奏快。",
                        "标题、节奏、卖点表达",
                        "人工挑选的同类型样例"
                )
        );

        assertThat(response.competitorBvId()).isEqualTo("BV1xK4y1a7Bc");
        assertThat(response.competitorVideoName()).isEqualTo("竞品视频A");
        assertThat(response.category()).isEqualTo("AI 工具教程");
        assertThat(competitorMapper.savedSample).isNotNull();
        assertThat(competitorMapper.savedSample.getCompetitorBvId()).isEqualTo("BV1xK4y1a7Bc");
        assertThat(competitorMapper.savedSample.getCompetitorVideoName()).isEqualTo("竞品视频A");
        verify(invalidationMapper).invalidateCompetitorReport("task-1");
        verify(invalidationMapper).invalidateCreatorReport("task-1");
        verify(invalidationMapper).invalidateGeneratedPreference("task-1");
        assertThat(taskMapper.updatedStatus).isEqualTo(CreatorTaskStatus.FEEDBACK_ANALYZED.name());
    }

    @Test
    void shouldAnalyzeCompetitorVideo() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.competitorVideoRecord = createCompetitorVideoRecord();
        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                feedbackMapper,
                competitorMapper,
                invalidationMapper,
                null,
                new FixedLlmService(COMPLETE_COMPETITOR_OUTPUT),
                new ObjectMapper(),
                new StubPromptService());

        CreatorCompetitorReportResponse response = service.analyze(
                "task-1",
                new CreatorCompetitorAnalyzeRequest(null, "重点对比标题", null)
        );

        assertThat(response.competitorSummary()).isEqualTo("竞品更强调结果");
        assertThat(response.ownDisadvantages()).contains("卖点不够前置");
        assertThat(response.parseStatus()).isEqualTo("PARSED");
        assertThat(taskMapper.updatedStatus).isEqualTo(CreatorTaskStatus.COMPETITOR_ANALYZED.name());
        verify(invalidationMapper).invalidateCreatorReport("task-1");
        verify(invalidationMapper).invalidateGeneratedPreference("task-1");
    }

    @Test
    void shouldFailWhenSampleMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                feedbackMapper,
                new FakeCreatorCompetitorMapper(),
                mock(CreatorReviewInvalidationMapper.class),
                null,
                new FixedLlmService("{}"),
                new ObjectMapper(),
                new StubPromptService());

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先提交同类型竞品视频");
    }

    @Test
    void shouldRejectBeforeFeedbackAnalysisCompletes() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.taskRecord.setStatus(CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.competitorVideoRecord = createCompetitorVideoRecord();
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);
        FixedLlmService llmService = new FixedLlmService("{}");

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                competitorMapper,
                invalidationMapper,
                null,
                llmService,
                new ObjectMapper(),
                new StubPromptService());

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先完成评论弹幕分析");

        assertThat(competitorMapper.savedReport).isNull();
        assertThat(taskMapper.updatedStatus).isNull();
        assertThat(llmService.callCount).isZero();
        verifyNoInteractions(invalidationMapper);
    }

    @Test
    void shouldNotPersistWhenStructuredContentIsIncomplete() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();
        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.competitorVideoRecord = createCompetitorVideoRecord();
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                feedbackMapper,
                competitorMapper,
                invalidationMapper,
                null,
                new FixedLlmService("""
                        {"competitorSummary":"竞品总结","competitorAdvantages":[],"ownAdvantages":[],"ownDisadvantages":[],"gapAnalysis":[],"improvementSuggestions":[],"differentiationStrategy":"差异化策略"}
                        """),
                new ObjectMapper(),
                new StubPromptService());

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式或内容不完整");

        assertThat(competitorMapper.savedReport).isNull();
        assertThat(taskMapper.updatedStatus).isNull();
        verifyNoInteractions(invalidationMapper);
    }

    private CreatorTaskRecord createTaskRecord() {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setUserId("default");
        record.setTaskName("竞品分析任务");
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
        record.setContent("这里是本视频文稿");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorFeedbackReportRecord createFeedbackReportRecord() {
        CreatorFeedbackReportRecord record = new CreatorFeedbackReportRecord();
        record.setId(1L);
        record.setReportId("feedback-report-1");
        record.setTaskId("task-1");
        record.setParseStatus("PARSED");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private CreatorCompetitorSampleRecord createCompetitorVideoRecord() {
        CreatorCompetitorSampleRecord record = new CreatorCompetitorSampleRecord();
        record.setId(1L);
        record.setCompetitorBvId("BV1xK4y1a7Bc");
        record.setCompetitorVideoName("竞品视频A");
        record.setTaskId("task-1");
        record.setCategory("AI 工具教程");
        record.setCompetitorSamples("竞品A：标题突出收益，评论说节奏快。竞品B：封面明确写结果。");
        record.setCompareDimension("标题、节奏、卖点表达");
        record.setExtraContext("人工挑选的同类型样例");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private static class FixedLlmService extends LLMService {

        private final String response;
        private int callCount;

        FixedLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            callCount++;
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
        public List<CreatorTaskSummaryRecord> listTasksByUser(String userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CreatorTaskSummaryRecord> listRecentTasks(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskStatus(String taskId, String status) {
            this.updatedStatus = status;
            return 1;
        }

        @Override
        public int markPlanningSkipped(String taskId) {
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

        @Override
        public int upsert(CreatorSuggestionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskId(String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskIdAndSessionId(String taskId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<CreatorSuggestionRecord> findBySuggestionId(String suggestionId) {
            return Optional.empty();
        }
    }

    private static class FakeCreatorFeedbackMapper implements CreatorFeedbackMapper {

        private CreatorFeedbackReportRecord reportRecord;

        @Override
        public int upsertFeedback(CreatorFeedbackRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorFeedbackRecord> findFeedbackByTaskId(String taskId) {
            return Optional.empty();
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
        private CreatorCompetitorReportRecord savedReport;
        private CreatorCompetitorSampleRecord savedSample;

        @Override
        public int upsertCompetitorVideo(CreatorCompetitorSampleRecord record) {
            this.savedSample = record;
            this.competitorVideoRecord = record;
            return 1;
        }

        @Override
        public Optional<CreatorCompetitorSampleRecord> findCompetitorVideoByTaskId(String taskId) {
            return Optional.ofNullable(competitorVideoRecord);
        }

        @Override
        public int upsertReport(CreatorCompetitorReportRecord record) {
            this.savedReport = record;
            return 1;
        }

        @Override
        public Optional<CreatorCompetitorReportRecord> findReportByTaskId(String taskId) {
            return Optional.ofNullable(savedReport);
        }

    }
}
