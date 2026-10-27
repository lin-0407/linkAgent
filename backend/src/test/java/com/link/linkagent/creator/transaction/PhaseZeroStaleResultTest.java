package com.link.linkagent.creator.transaction;

import com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.common.DocumentExtractionService;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalyzeRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalysisOutput;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.competitor.service.CreatorCompetitorService;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalysisOutput;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackEvidenceRetrievalService;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.interactive.service.CreatorInteractiveService;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalysisOutput;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.support.TestTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 0 事务整改的"旧结果拒绝"回归测试。
 * <p>
 * 为什么单独一个测试类：事务拆开以后，模型调用发生在事务外，推理期间用户可能改了想法、换了样例、
 * 改了材料，或者上游报告被重新生成。这些场景共同的要求是——本次推理结果必须被丢弃，
 * 不能落库，也不能推进任务状态。每个用例都断言两件事：抛冲突错误 + 没有任何写入。
 * <p>
 * 这里用 Mockito 模拟 Mapper、用 {@link TestTransactions} 执行事务回调，
 * 只验证业务判断顺序；真实事务边界由 {@code PhaseZeroTransactionBoundaryTest} 验证。
 */
class PhaseZeroStaleResultTest {

    @Test
    void feedbackAnalyzeShouldDropResultWhenSamplesChangedDuringGeneration() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(feedbackTask()));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(boundBinding()));
        // 生成时读到第一批样例，保存前复核时已经变成第二批：模拟分析期间用户重新导入或粘贴
        when(feedbackMapper.findFeedbackByTaskId("task-1"))
                .thenReturn(Optional.of(feedback("第一批样例")), Optional.of(feedback("第二批样例")));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorFeedbackAnalysisOutput.class)))
                .thenReturn(analysisOutput());

        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                bilibiliMapper,
                feedbackMapper,
                mock(CreatorReviewInvalidationMapper.class),
                llmService,
                new ObjectMapper(),
                TestTransactions.directTemplate(),
                mock(CreatorFeedbackEvidenceRetrievalService.class),
                promptServiceMirroringVariables(),
                mock(CreatorMediaWorkflowGateService.class)
        ,
                org.mockito.Mockito.mock(com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService.class));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorFeedbackAnalyzeRequest(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(feedbackMapper, never()).upsertReport(any());
        verify(taskMapper, never()).updateTaskStatus(anyString(), anyString());
    }

    @Test
    void understandingShouldDropResultWhenIdeaChangedDuringGeneration() {
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        LLMService llmService = mock(LLMService.class);
        when(mapper.findSessionByTaskId("task-1")).thenReturn(
                Optional.of(interactiveSession("想法A", "资料A")),
                Optional.of(interactiveSession("想法B", "资料A")));
        when(llmService.chat(anyString(), anyString())).thenReturn("这是我理解到的创作想法");

        CreatorInteractiveService service = interactiveService(mapper, llmService);

        assertThatThrownBy(() -> service.triggerUnderstanding("task-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 生成中状态先登记，但结果被丢弃时不能写回 READY
        verify(mapper).updateUnderstanding("task-1", null, "UNDERSTANDING");
        verify(mapper, never()).updateUnderstandingIfStatus(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void creativeOptionsShouldDropCardsWhenBackgroundChangedDuringGeneration() {
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        LLMService llmService = mock(LLMService.class);
        InteractiveSessionRecord before = interactiveSession("想法A", "资料A");
        before.setUnderstandingStatus("READY");
        when(mapper.findSessionByTaskId("task-1")).thenReturn(
                Optional.of(before),
                Optional.of(interactiveSession("想法A", "资料B")));
        // 返回不可解析的输出，服务会走内置兜底卡；无论走哪条路径，保存前都必须复核输入
        when(llmService.chat(anyString(), anyString())).thenReturn("不是 JSON");

        CreatorInteractiveService service = interactiveService(mapper, llmService);

        assertThatThrownBy(() -> service.generateCreativeOptions("task-1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mapper, never()).deleteOptionsBySessionId(anyString());
        verify(mapper, never()).insertOption(any());
        verify(mapper, never()).updateSessionGenerationResult(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void suggestionShouldDropCandidateWhenMaterialsChangedDuringGeneration() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        LLMService llmService = mock(LLMService.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(suggestionTask()));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(
                List.of(material("第一版文稿")),
                List.of(material("第二版文稿")));
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"contentSummary\":\"摘要\"}");

        PrePublishSuggestionService service = new PrePublishSuggestionService(
                taskMapper,
                suggestionMapper,
                new CreatorPreferenceService(mock(CreatorPreferenceMapper.class)),
                mock(CreatorContextService.class),
                null,
                llmService,
                null,
                new ObjectMapper(),
                promptServiceMirroringVariables(),
                null,
                null,
                TestTransactions.directTemplate()
        );

        assertThatThrownBy(() -> service.generateSuggestion(
                "task-1",
                new PrePublishAnalyzeRequest(null, null, null, null, "IGNORE_HISTORY", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(suggestionMapper, never()).upsert(any());
    }

    @Test
    void reportShouldDropResultWhenFeedbackReportChangedDuringGeneration() throws Exception {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        CreatorCompetitorMapper competitorMapper = mock(CreatorCompetitorMapper.class);
        CreatorReportMapper reportMapper = mock(CreatorReportMapper.class);
        LLMService llmService = mock(LLMService.class);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(reportTask()));
        when(taskMapper.listTasksByUser(anyString(), anyInt())).thenReturn(List.of());
        when(feedbackMapper.findReportByTaskId("task-1")).thenReturn(
                Optional.of(feedbackReport("旧反馈摘要")),
                Optional.of(feedbackReport("新反馈摘要")));
        when(competitorMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(competitorReport()));
        when(feedbackMapper.findMetricByTaskId("task-1")).thenReturn(Optional.of(metric()));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorReportAnalysisOutput.class)))
                .thenReturn(new ObjectMapper().readValue(REPORT_OUTPUT_JSON, CreatorReportAnalysisOutput.class));

        CreatorReportService service = new CreatorReportService(
                taskMapper,
                feedbackMapper,
                competitorMapper,
                reportMapper,
                mock(CreatorPreferenceService.class),
                llmService,
                new ObjectMapper(),
                promptServiceMirroringVariables(),
                mock(CreatorMediaWorkflowGateService.class),
                TestTransactions.directTemplate()
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(reportMapper, never()).upsert(any());
        verify(taskMapper, never()).updateTaskStatus(anyString(), anyString());
    }

    @Test
    void competitorShouldDropResultWhenFeedbackReportChangedDuringGeneration() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        CreatorCompetitorMapper competitorMapper = mock(CreatorCompetitorMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        LLMService llmService = mock(LLMService.class);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(competitorTask()));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(material("本视频文稿")));
        when(feedbackMapper.findReportByTaskId("task-1")).thenReturn(
                Optional.of(feedbackReport("旧反馈摘要")),
                Optional.of(feedbackReport("新反馈摘要")));
        when(competitorMapper.findCompetitorVideoByTaskId("task-1")).thenReturn(Optional.of(competitorSample()));
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.empty());
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorCompetitorAnalysisOutput.class)))
                .thenAnswer(invocation -> new ObjectMapper().readValue("""
            {"competitorSummary":"竞品更强调结果","competitorAdvantages":[{"advantage":"标题更直接","evidence":"样例标题都突出收益","lesson":"标题前置结果"}],"ownAdvantages":[{"advantage":"解释更细","evidence":"文稿结构完整"}],"ownDisadvantages":[{"disadvantage":"卖点不够前置","evidence":"标题偏平","risk":"点击弱"}],"gapAnalysis":[{"dimension":"标题","gap":"结果感弱","priority":"HIGH"}],"improvementSuggestions":[{"suggestion":"标题突出收益","reason":"竞品样例有效","action":"重写标题"}],"differentiationStrategy":"主打可复制步骤"}
            """, CreatorCompetitorAnalysisOutput.class));

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                suggestionMapper,
                feedbackMapper,
                competitorMapper,
                mock(CreatorReviewInvalidationMapper.class),
                null,
                llmService,
                new ObjectMapper(),
                promptServiceMirroringVariables(),
                TestTransactions.directTemplate()
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(competitorMapper, never()).upsertReport(any());
        verify(taskMapper, never()).updateTaskStatus(anyString(), anyString());
    }

    private static final String REPORT_OUTPUT_JSON = """
            {"contentSummary":"总结","coreSellingPoints":["卖点"],"titleDescriptionReview":{"titleConclusion":"标题合适","descriptionConclusion":"简介清楚","tagAndPartitionConclusion":"分区准确","riskReminder":"注意风险"},"audienceFeedbackSummary":"反馈","competitorComparison":{"benchmarkConclusion":"对标","ownAdvantages":["优势"],"ownDisadvantages":["短板"],"differentiationStrategy":"差异化"},"controversyAndMisunderstanding":[{"point":"争议","impact":"中","action":"解释"}],"nextActionSuggestions":[{"suggestion":"下一期","reason":"观众想看","priority":"HIGH"}],"creatorPreferenceInsight":["偏好"],"overallConclusion":"结论"}
            """;

    /**
     * 提示词渲染必须跟着变量走，否则"输入变了"在测试里根本看不出来。
     * 真实模板也是这个性质：样例、材料、上游报告变了，渲染结果就不同。
     */
    private PromptService promptServiceMirroringVariables() {
        PromptService promptService = mock(PromptService.class);
        when(promptService.get(anyString())).thenReturn("系统提示词");
        when(promptService.render(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.toString();
        });
        return promptService;
    }

    private CreatorInteractiveService interactiveService(CreatorInteractiveMapper mapper, LLMService llmService) {
        return new CreatorInteractiveService(
                mapper,
                mock(CreatorTaskService.class),
                llmService,
                new ObjectMapper(),
                mock(DocumentExtractionService.class),
                TestTransactions.directTemplate()
        );
    }

    private InteractiveSessionRecord interactiveSession(String idea, String backgroundContext) {
        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setTaskId("task-1");
        session.setSessionId("session-1");
        session.setUserId("default");
        session.setIdea(idea);
        session.setVideoType("知识科普");
        session.setBackgroundContext(backgroundContext);
        session.setUnderstandingStatus("NONE");
        session.setStatus("IDEA_INPUT");
        return session;
    }

    private CreatorTaskRecord feedbackTask() {
        return task("反馈任务", CreatorTaskStatus.FEEDBACK_COLLECTING.name());
    }

    private CreatorTaskRecord suggestionTask() {
        return task("发布前优化任务", CreatorTaskStatus.DRAFT.name());
    }

    private CreatorTaskRecord reportTask() {
        return task("复盘任务", CreatorTaskStatus.COMPETITOR_ANALYZED.name());
    }

    private CreatorTaskRecord competitorTask() {
        return task("竞品分析任务", CreatorTaskStatus.FEEDBACK_ANALYZED.name());
    }

    private CreatorTaskRecord task(String taskName, String status) {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setUserId("default");
        task.setTaskName(taskName);
        task.setVideoType("知识科普");
        task.setStatus(status);
        return task;
    }

    private CreatorFeedbackRecord feedback(String commentSamples) {
        CreatorFeedbackRecord feedback = new CreatorFeedbackRecord();
        feedback.setFeedbackId("feedback-1");
        feedback.setTaskId("task-1");
        feedback.setCommentSamples(commentSamples);
        feedback.setDanmakuSamples("学到了");
        return feedback;
    }

    private TaskVideoBindingRecord boundBinding() {
        TaskVideoBindingRecord binding = new TaskVideoBindingRecord();
        binding.setBindingId("binding-1");
        binding.setTaskId("task-1");
        binding.setUserId("default");
        binding.setBilibiliUid("27058248");
        binding.setBvid("BV1xx411c7mD");
        binding.setBindingStatus("BOUND");
        return binding;
    }

    private CreatorFeedbackReportRecord feedbackReport(String summary) {
        CreatorFeedbackReportRecord record = new CreatorFeedbackReportRecord();
        record.setId(1L);
        record.setReportId("feedback-report-1");
        record.setTaskId("task-1");
        record.setFeedbackSummary(summary);
        record.setRawOutput("{}");
        record.setParseStatus("PARSED");
        return record;
    }

    private com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord competitorReport() {
        com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord record =
                new com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord();
        record.setId(1L);
        record.setReportId("competitor-report-1");
        record.setTaskId("task-1");
        record.setCompetitorSummary("竞品整体打法");
        record.setRawOutput("{}");
        record.setParseStatus("PARSED");
        return record;
    }

    private CreatorCompetitorSampleRecord competitorSample() {
        CreatorCompetitorSampleRecord record = new CreatorCompetitorSampleRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setCompetitorBvId("BV1xK4y1a7Bc");
        record.setCompetitorVideoName("竞品视频A");
        record.setCompetitorSamples("竞品A：标题突出收益。");
        return record;
    }

    private CreatorFeedbackMetricRecord metric() {
        CreatorFeedbackMetricRecord record = new CreatorFeedbackMetricRecord();
        record.setMetricId("metric-1");
        record.setTaskId("task-1");
        record.setViewCount(1000L);
        record.setLikeCount(120L);
        return record;
    }

    private CreatorMaterialRecord material(String content) {
        CreatorMaterialRecord record = new CreatorMaterialRecord();
        record.setId(1L);
        record.setTaskId("task-1");
        record.setMaterialType("MANUSCRIPT");
        record.setContent(content);
        return record;
    }

    private CreatorFeedbackAnalysisOutput analysisOutput() {
        return new CreatorFeedbackAnalysisOutput(
                "整体反馈积极",
                "需要兼顾深度与节奏",
                "观众希望补充完整示例",
                List.of(new CreatorFeedbackAnalysisOutput.HotTopic(
                        "完整示例",
                        "证据充分",
                        "下一期补充",
                        "给出可运行代码"
                )),
                "整体偏正向",
                List.of(),
                List.of(),
                List.of(),
                List.of(new CreatorFeedbackAnalysisOutput.NextContentSuggestion(
                        "完整示例后续篇",
                        "评论高频请求",
                        "补充可运行项目",
                        "避免重复本期内容"
                )),
                List.of(),
                List.of(new CreatorFeedbackAnalysisOutput.FeedbackAction(
                        "HIGH",
                        "补充完整示例",
                        "高频反馈",
                        "降低理解成本"
                ))
        );
    }
}
