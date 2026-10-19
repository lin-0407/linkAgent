package com.link.linkagent.creator.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.support.TestTransactions;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.model.CreatorReportResponse;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.link.linkagent.creator.report.model.CreatorReportHistorySummary;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class CreatorReportServiceTest {

    @Test
    void shouldGenerateReportFromPostPublishDataWithoutPlanningInputs() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.taskRecord.setPlanningSkipped(true);
        taskMapper.taskRecord.setTaskName("Codex 复盘历史实测");

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();
        feedbackMapper.metricRecord = createFeedbackMetricRecord();

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.reportRecord = createCompetitorReportRecord();

        FakeCreatorReportMapper reportMapper = new FakeCreatorReportMapper();
        FakeCreatorPreferenceMapper preferenceMapper = new FakeCreatorPreferenceMapper();
        FixedLlmService llmService = new FixedLlmService("""
                {"contentSummary":"本期内容总结","coreSellingPoints":["卖点1"],"titleDescriptionReview":{"titleConclusion":"标题合适","descriptionConclusion":"简介清楚","tagAndPartitionConclusion":"分区准确","riskReminder":"注意风险"},"audienceFeedbackSummary":"反馈不错","competitorComparison":{"benchmarkConclusion":"对标清楚","ownAdvantages":["优势"],"ownDisadvantages":["短板"],"differentiationStrategy":"做差异化"},"controversyAndMisunderstanding":[{"point":"争议点","impact":"中等","action":"继续解释"}],"nextActionSuggestions":[{"suggestion":"做下一期","reason":"观众想看","priority":"HIGH"}],"creatorPreferenceInsight":["偏好干货表达"],"overallConclusion":"适合继续做"}
                """);
        CreatorReportService service = new CreatorReportService(
                taskMapper,
                feedbackMapper,
                competitorMapper,
                reportMapper,
                new CreatorPreferenceService(preferenceMapper),
                llmService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ReportPromptService(),
                mock(CreatorMediaWorkflowGateService.class),
                TestTransactions.directTemplate(), new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        var businessAgent=mock(com.link.linkagent.assistant.CreatorBusinessAgentService.class);
        when(businessAgent.review(org.mockito.ArgumentMatchers.eq("task-1"),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("\n分工分析意见：先解释观众疑问");
        service.setBusinessAgent(businessAgent);
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
        assertThat(llmService.lastUserMessage)
                .contains("播放量：1000", "反馈摘要", "竞品整体打法", "task-1",
                        "任务名称（包括历史任务名称）仅是工作台管理标识", "缺少原文时明确无法评价")
                .doesNotContain("创作素材", "发布前建议结果", "制作蓝图", "Codex 复盘历史实测");
        assertThat(llmService.lastUserMessage).contains("分工分析意见：先解释观众疑问");
        verify(businessAgent).review(org.mockito.ArgumentMatchers.eq("task-1"),org.mockito.ArgumentMatchers.argThat(
                source -> source.contains("反馈摘要") && !source.contains("制作蓝图")));
        assertThat(llmService.lastSystemPrompt)
                .contains("不得据此推断视频讲了什么", "不能把建议改写成已发布事实",
                        "只序列化一次", "合法反斜杠必须保持不变");
    }

    @Test
    void shouldFailWhenFeedbackReportMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.reportRecord = createCompetitorReportRecord();

        CreatorReportService service = new CreatorReportService(
                taskMapper,
                feedbackMapper,
                competitorMapper,
                new FakeCreatorReportMapper(),
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class),
                TestTransactions.directTemplate(), new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先完成评论弹幕分析");
    }

    private static final String VALID_REPORT_JSON = """
            {"contentSummary":"本期内容总结","coreSellingPoints":["卖点"],"titleDescriptionReview":{"titleConclusion":"标题合适","descriptionConclusion":"简介清楚","tagAndPartitionConclusion":"分区准确","riskReminder":"注意风险"},"audienceFeedbackSummary":"反馈不错","competitorComparison":{"benchmarkConclusion":"对标清楚","ownAdvantages":["优势"],"ownDisadvantages":["短板"],"differentiationStrategy":"做差异化"},"controversyAndMisunderstanding":[],"nextActionSuggestions":[{"suggestion":"做下一期","reason":"观众想看","priority":"HIGH"}],"creatorPreferenceInsight":["偏好干货表达"],"overallConclusion":"适合继续做"}
            """;

    @Test
    void shouldExcludeCurrentAndHistoricalTaskNamesFromCrossPeriodPrompt() {
        Fixture f = fixture();
        f.tasks.taskRecord.setTaskName("黑洞形成原理课堂");
        CreatorTaskSummaryRecord historicalTask = new CreatorTaskSummaryRecord();
        historicalTask.setTaskId("historical-task-astronomy");
        historicalTask.setTaskName("火星殖民计划详解");
        historicalTask.setStatus(CreatorTaskStatus.ANALYZED.name());
        f.tasks.recentTasks = List.of(historicalTask);

        // 用实际历史报告内容确认跨期分支已执行，避免空列表让名称省略断言误通过。
        CreatorReportRecord historicalReport = new CreatorReportRecord();
        historicalReport.setParseStatus("PARSED");
        historicalReport.setOverallConclusion("历史评论样本尚不足以确定题材");
        historicalReport.setCoreSellingPoints("历史样本有一条明确鼓励");
        historicalReport.setAudienceFeedbackSummary("历史反馈主要是泛化玩梗");
        historicalReport.setNextActionSuggestions("先补充具体内容反馈");
        f.reports.historicalReports.put(historicalTask.getTaskId(), historicalReport);

        f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));

        assertThat(f.llm.lastUserMessage)
                .contains("task-1", "第1期任务ID：historical-task-astronomy",
                        "历史评论样本尚不足以确定题材", "先补充具体内容反馈")
                .doesNotContain("黑洞形成原理课堂", "火星殖民计划详解");
    }

    @Test
    void shouldGenerateAndSnapshotReportWithoutCompetitor() {
        Fixture f = fixture();
        CreatorReportResponse response = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        assertThat(response.competitorComparison()).contains("未提供竞品分析", "无法判断相对优势")
                .doesNotContain("对标清楚", "做差异化");
        assertThat(f.llm.lastUserMessage).contains("未提供完整竞品分析");
        assertThat(f.service.listHistory("task-1", 1, 10).total()).isEqualTo(1);
        assertThat(f.service.listHistory("task-1", 1, 10).items().getFirst().hasCompetitor()).isFalse();
        assertThat(f.service.getHistory("task-1", response.reportId())).isEqualTo(response);
    }

    @Test
    void shouldPreserveFirstSnapshotAfterCompetitorInvalidatesLatestAndRegeneration() {
        Fixture f = fixture();
        CreatorReportResponse first = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        // 模拟补充竞品后只失效当前报告，历史仍然可读。
        f.reports.savedRecord = null;
        f.competitors.reportRecord = createCompetitorReportRecord();
        f.tasks.taskRecord.setStatus(CreatorTaskStatus.COMPETITOR_ANALYZED.name());
        f.llm.response = VALID_REPORT_JSON.replace("本期内容总结", "补充竞品后的总结");
        CreatorReportResponse second = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        assertThat(second.reportId()).isNotEqualTo(first.reportId());
        assertThat(second.competitorComparison()).contains("对标清楚");
        assertThat(f.service.getHistory("task-1", first.reportId())).isEqualTo(first);
        assertThat(f.service.listHistory("task-1", 1, 10).items()).hasSize(2);
        assertThat(f.service.listHistory("task-1", 1, 10).items().getFirst().hasCompetitor()).isTrue();
    }

    @Test
    void shouldKeepLatestAndHistoryWhenRegenerationFails() {
        Fixture f = fixture();
        CreatorReportResponse first = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        f.llm.response = "{}";
        assertThatThrownBy(() -> f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.service.getReport("task-1")).isEqualTo(first);
        assertThat(f.service.listHistory("task-1", 1, 10).total()).isEqualTo(1);
    }

    @Test
    void shouldDropReportWhenCompetitorArrivesDuringGeneration() {
        Fixture f = fixture();
        f.llm.duringCall = () -> f.competitors.reportRecord = createCompetitorReportRecord();
        assertThatThrownBy(() -> f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("发生变化");
        assertThat(f.reports.savedRecord).isNull();
        assertThat(f.service.listHistory("task-1", 1, 10).total()).isZero();
    }

    @Test
    void shouldBackfillLegacyLatestOnceAndPaginateWithoutOverwritingSnapshots() {
        Fixture f = fixture();
        CreatorReportResponse first = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        // 清空历史模拟功能升级前只有最新报告的情况。
        f.reports.snapshots.clear();
        for (int i = 0; i < 11; i++) {
            f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        }
        assertThat(f.service.getHistory("task-1", first.reportId())).isEqualTo(first);
        assertThat(f.service.listHistory("task-1", 1, 10).total()).isEqualTo(12);
        assertThat(f.service.listHistory("task-1", 2, 10).items()).hasSize(2);
        assertThat(f.service.listHistory("task-1", 2, 10).items().getLast().hasCompetitor()).isNull();
        assertThatThrownBy(() -> f.service.getHistory("task-1", "another-task-history"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("复盘历史不存在");
    }

    @Test
    void shouldNotReturnAnotherTasksSnapshot() {
        Fixture f = fixture();
        CreatorReportResponse first = f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));
        f.tasks.taskRecord.setTaskId("task-2");
        assertThatThrownBy(() -> f.service.getHistory("task-2", first.reportId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("复盘历史不存在");
        assertThat(f.service.listHistory("task-2", 1, 10).total()).isZero();
    }

    @Test
    void shouldAppendPublishedMetadataEvenWhenLegacyReportTemplateOmitsIt() {
        Fixture f = fixture();
        when(f.published.buildPrompt(any())).thenReturn("正式标题：BV核验后的标题\n正式简介：公开简介原文\n标签：未提供");
        f.service.analyze("task-1", new CreatorReportAnalyzeRequest(null,null,null));
        assertThat(f.llm.lastUserMessage).contains("【自身已绑定视频的正式发布资料】", "BV核验后的标题", "公开简介原文", "标签：未提供")
                .doesNotContain(f.tasks.taskRecord.getTaskName());
    }

    @Test
    void shouldDropReportAndHistoryWhenPublishedSnapshotChangesDuringModelCall() {
        Fixture f = fixture();
        when(f.published.buildPrompt(any())).thenReturn("正式标题：旧标题", "正式标题：同步后的新标题");
        assertThatThrownBy(() -> f.service.analyze("task-1",new CreatorReportAnalyzeRequest(null,null,null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("发布资料").hasMessageContaining("发生变化");
        assertThat(f.reports.savedRecord).isNull();
        assertThat(f.service.listHistory("task-1",1,10).total()).isZero();
        assertThat(f.tasks.updatedStatus).isNull();
    }

    private record Fixture(CreatorReportService service, FakeCreatorTaskMapper tasks,
                           FakeCreatorCompetitorMapper competitors, FakeCreatorReportMapper reports, FixedLlmService llm,
                           CreatorPublishedVideoContextService published) {}

    private Fixture fixture() {
        FakeCreatorTaskMapper tasks = new FakeCreatorTaskMapper();
        tasks.taskRecord = createTaskRecord();
        tasks.taskRecord.setStatus(CreatorTaskStatus.FEEDBACK_ANALYZED.name());
        FakeCreatorFeedbackMapper feedback = new FakeCreatorFeedbackMapper();
        feedback.reportRecord = createFeedbackReportRecord();
        FakeCreatorCompetitorMapper competitors = new FakeCreatorCompetitorMapper();
        FakeCreatorReportMapper reports = new FakeCreatorReportMapper();
        FixedLlmService llm = new FixedLlmService(VALID_REPORT_JSON);
        CreatorPublishedVideoContextService published = mock(CreatorPublishedVideoContextService.class);
        when(published.buildPrompt(any())).thenReturn("未提供（无已核验BV的公开资料）");
        CreatorReportService service = new CreatorReportService(tasks, feedback, competitors, reports,
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()), llm,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), new ReportPromptService(),
                mock(CreatorMediaWorkflowGateService.class), TestTransactions.directTemplate(), published);
        return new Fixture(service, tasks, competitors, reports, llm, published);
    }

    @Test
    void shouldNotPersistReportWhenStructuredFieldsAreMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();

        FakeCreatorFeedbackMapper feedbackMapper = new FakeCreatorFeedbackMapper();
        feedbackMapper.reportRecord = createFeedbackReportRecord();

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.reportRecord = createCompetitorReportRecord();

        FakeCreatorReportMapper reportMapper = new FakeCreatorReportMapper();
        FakeCreatorPreferenceMapper preferenceMapper = new FakeCreatorPreferenceMapper();
        CreatorReportService service = new CreatorReportService(
                taskMapper,
                feedbackMapper,
                competitorMapper,
                reportMapper,
                new CreatorPreferenceService(preferenceMapper),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mock(CreatorMediaWorkflowGateService.class),
                TestTransactions.directTemplate(), new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        assertThatThrownBy(() -> service.analyze(
                "task-1",
                new CreatorReportAnalyzeRequest(null, null, null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结构化输出解析失败");

        assertThat(reportMapper.savedRecord).isNull();
        assertThat(taskMapper.updatedStatus).isNull();
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
                new FakeCreatorFeedbackMapper(),
                new FakeCreatorCompetitorMapper(),
                reportMapper,
                new CreatorPreferenceService(new FakeCreatorPreferenceMapper()),
                new FixedLlmService("{}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StubPromptService(),
                mediaWorkflowGateService,
                TestTransactions.directTemplate()
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mediaWorkflowGateService).ensureMediaEnabled("创作复盘");
        verify(mediaWorkflowGateService).ensureReadyForPostPublish("task-1", "default", "创作复盘");
        assertThat(reportMapper.savedRecord).isNull();
        assertThat(taskMapper.updatedStatus).isNull();
    }

    private CreatorTaskRecord createTaskRecord() {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setUserId("default");
        record.setTaskName("复盘任务");
        record.setStatus(CreatorTaskStatus.COMPETITOR_ANALYZED.name());
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

    private CreatorFeedbackMetricRecord createFeedbackMetricRecord() {
        CreatorFeedbackMetricRecord record = new CreatorFeedbackMetricRecord();
        record.setMetricId("metric-1");
        record.setTaskId("task-1");
        record.setViewCount(1000L);
        record.setLikeCount(120L);
        record.setCoinCount(30L);
        record.setFavoriteCount(45L);
        record.setShareCount(12L);
        record.setSource("BILIBILI_FEEDBACK_IMPORT");
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

        private String response;
        private Runnable duringCall = () -> {};
        private String lastUserMessage;
        private String lastSystemPrompt;

        FixedLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
            this.lastUserMessage = userMessage;
            this.lastSystemPrompt = systemPrompt;
            duringCall.run();
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response, type);
            } catch (Exception exception) {
                throw new IllegalArgumentException("结构化输出解析失败：" + exception.getMessage(), exception);
            }
        }
    }

    private static class ReportPromptService extends StubPromptService {

        @Override
        public String get(String key) {
            if ("report.user".equals(key)) {
                return """
                        任务：{taskName}（ID: {taskId}）
                        自定义指导：{customGuidance}
                        复盘重点：{reviewFocus}
                        额外要求：{extraRequirement}
                        已发布视频指标：
                        {videoMetrics}
                        观众反馈分析：
                        {feedbackResult}
                        竞品分析：
                        {competitorResult}
                        历史发布后复盘：
                        {crossPeriodContext}
                        """;
            }
            return super.get(key);
        }
    }

    private static class FakeCreatorTaskMapper implements CreatorTaskMapper {

        private CreatorTaskRecord taskRecord;
        private String updatedStatus;
        private List<CreatorTaskSummaryRecord> recentTasks = List.of();

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
            return List.of();
        }

        @Override
        public List<com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord> listTasksByUser(String userId, int limit) {
            return recentTasks.stream().limit(limit).toList();
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

    private static class FakeCreatorFeedbackMapper implements CreatorFeedbackMapper {

        private com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord feedbackRecord;
        private CreatorFeedbackReportRecord reportRecord;
        private CreatorFeedbackMetricRecord metricRecord;

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
        public Optional<CreatorFeedbackMetricRecord> findMetricByTaskId(String taskId) {
            return Optional.ofNullable(metricRecord);
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
        private final Map<String, CreatorReportRecord> historicalReports = new LinkedHashMap<>();

        @Override
        public int upsert(CreatorReportRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<CreatorReportRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(historicalReports.getOrDefault(taskId, savedRecord));
        }
        private final Map<String, String> snapshots = new LinkedHashMap<>();
        private final Map<String, Boolean> includedCompetitor = new LinkedHashMap<>();

        @Override
        public int insertHistory(String historyId, String taskId, String feedbackReportId, String competitorReportId, String snapshot) {
            String key = taskId + ":" + historyId;
            if (snapshots.containsKey(key)) return 0;
            snapshots.put(key, snapshot);
            includedCompetitor.put(key, feedbackReportId == null ? null : competitorReportId != null);
            return 1;
        }

        @Override
        public List<CreatorReportHistorySummary> listHistory(String taskId, int size, int offset) {
            List<CreatorReportHistorySummary> rows = new ArrayList<>();
            snapshots.forEach((key, snapshot) -> {
                if (key.startsWith(taskId + ":")) rows.add(new CreatorReportHistorySummary(
                        key.substring(taskId.length() + 1), "摘要", includedCompetitor.get(key), LocalDateTime.now()));
            });
            return rows.reversed().stream().skip(offset).limit(size).toList();
        }

        @Override
        public long countHistory(String taskId) {
            return snapshots.keySet().stream().filter(key -> key.startsWith(taskId + ":")).count();
        }

        @Override
        public Optional<String> findHistorySnapshot(String taskId, String historyId) {
            return Optional.ofNullable(snapshots.get(taskId + ":" + historyId));
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
