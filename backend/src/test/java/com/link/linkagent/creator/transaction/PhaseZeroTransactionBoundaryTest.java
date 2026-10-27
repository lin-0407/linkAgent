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
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.competitor.service.CreatorCompetitorService;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalysisOutput;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackEvidenceRetrievalService;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionRecord;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.interactive.service.CreatorInteractiveService;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalysisOutput;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 0 事务边界的真实验证。
 * <p>
 * 为什么不能用 Mockito 假事务模板代替：假模板只能证明"回调被执行了"，不能证明
 * "模型调用时没有活动事务、落库时在事务里、落库失败会回滚"。这里用真实的
 * {@link DataSourceTransactionManager}（数据源和连接用 mock，不连真库）搭出真事务，
 * 在模型调用点和 Mapper 写入点分别读 {@link TransactionSynchronizationManager} 的当前状态。
 */
class PhaseZeroTransactionBoundaryTest {

    @Test
    void feedbackAnalyzeShouldCallModelOutsideTransactionAndSaveInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicReference<CreatorFeedbackReportRecord> savedReport = new AtomicReference<>();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean saveHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(feedbackTask()));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(boundBinding()));
        when(feedbackMapper.findFeedbackByTaskId("task-1")).thenReturn(Optional.of(feedback()));
        when(feedbackMapper.upsertReport(any(CreatorFeedbackReportRecord.class))).thenAnswer(invocation -> {
            saveHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            savedReport.set(invocation.getArgument(0));
            return 1;
        });
        when(feedbackMapper.findReportByTaskId("task-1"))
                .thenAnswer(invocation -> Optional.ofNullable(savedReport.get()));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorFeedbackAnalysisOutput.class)))
                .thenAnswer(invocation -> {
                    modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return analysisOutput();
                });

        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                bilibiliMapper,
                feedbackMapper,
                mock(CreatorReviewInvalidationMapper.class),
                llmService,
                new ObjectMapper(),
                transactionTemplate(dataSource),
                mock(CreatorFeedbackEvidenceRetrievalService.class),
                promptServiceMirroringVariables(),
                mock(CreatorMediaWorkflowGateService.class)
        ,
                org.mockito.Mockito.mock(com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService.class));

        service.analyze("task-1", new CreatorFeedbackAnalyzeRequest(null, null, null));

        // 模型调用点必须没有活动事务，否则推理几十秒会把数据库连接一直占住
        assertThat(modelCallHadTransaction).isFalse();
        // 落库阶段必须在事务里，保证报告、下游失效和任务状态一起提交
        assertThat(saveHadTransaction).isTrue();
    }

    @Test
    void feedbackAnalyzeShouldRollbackWhenSaveFails() throws Exception {
        DataSource dataSource = mockDataSource();
        Connection connection = dataSource.getConnection();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(feedbackTask()));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(boundBinding()));
        when(feedbackMapper.findFeedbackByTaskId("task-1")).thenReturn(Optional.of(feedback()));
        when(feedbackMapper.upsertReport(any(CreatorFeedbackReportRecord.class)))
                .thenThrow(new IllegalStateException("模拟报告写入失败"));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorFeedbackAnalysisOutput.class)))
                .thenReturn(analysisOutput());

        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                bilibiliMapper,
                feedbackMapper,
                mock(CreatorReviewInvalidationMapper.class),
                llmService,
                new ObjectMapper(),
                transactionTemplate(dataSource),
                mock(CreatorFeedbackEvidenceRetrievalService.class),
                promptServiceMirroringVariables(),
                mock(CreatorMediaWorkflowGateService.class)
        ,
                org.mockito.Mockito.mock(com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService.class));

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorFeedbackAnalyzeRequest(null, null, null)))
                .isInstanceOf(IllegalStateException.class);

        // 落库失败必须回滚，不能只提交一半（例如任务状态已推进但报告没写）
        verify(connection).rollback();
    }

    @Test
    void feedbackImportShouldClassifyOutsideTransactionAndReplaceItemsInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicBoolean classifyHadTransaction = new AtomicBoolean(true);
        AtomicBoolean insertHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(feedbackTask()));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(boundBinding()));
        when(llmService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            classifyHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "[{\"index\":0,\"category\":\"QUESTION\",\"sentiment\":\"NEUTRAL\",\"reason\":\"提问\"},"
                    + "{\"index\":1,\"category\":\"EMOTION\",\"sentiment\":\"POSITIVE\",\"reason\":\"情绪\"}]";
        });
        when(feedbackMapper.insertItem(any(CreatorFeedbackItemRecord.class))).thenAnswer(invocation -> {
            insertHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                bilibiliMapper,
                feedbackMapper,
                mock(CreatorReviewInvalidationMapper.class),
                llmService,
                new ObjectMapper(),
                transactionTemplate(dataSource),
                mock(CreatorFeedbackEvidenceRetrievalService.class),
                promptServiceMirroringVariables(),
                mock(CreatorMediaWorkflowGateService.class)
        ,
                org.mockito.Mockito.mock(com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService.class));

        service.importFeedback("task-1", new MockMultipartFile(
                "file",
                "feedback.txt",
                "text/plain",
                TEXT_FEEDBACK.getBytes(StandardCharsets.UTF_8)));

        // 分类是一次模型调用，必须在事务外；旧明细软删和新明细插入必须同一事务
        assertThat(classifyHadTransaction).isFalse();
        assertThat(insertHadTransaction).isTrue();
    }

    @Test
    void understandingShouldCallModelOutsideTransactionAndWriteResultInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        LLMService llmService = mock(LLMService.class);
        InteractiveSessionRecord session = interactiveSession();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean resultWriteHadTransaction = new AtomicBoolean(false);

        when(mapper.findSessionByTaskId("task-1")).thenReturn(Optional.of(session));
        when(mapper.listOptionsBySessionId("session-1")).thenReturn(List.of());
        when(llmService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "这是我理解到的创作想法";
        });
        when(mapper.updateUnderstandingIfStatus(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    resultWriteHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return 1;
                });

        CreatorInteractiveService service = new CreatorInteractiveService(
                mapper,
                mock(CreatorTaskService.class),
                llmService,
                new ObjectMapper(),
                mock(DocumentExtractionService.class),
                transactionTemplate(dataSource)
        );

        service.triggerUnderstanding("task-1");

        assertThat(modelCallHadTransaction).isFalse();
        assertThat(resultWriteHadTransaction).isTrue();
    }

    @Test
    void creativeOptionsShouldCallModelOutsideTransactionAndStoreCardsInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorInteractiveMapper mapper = mock(CreatorInteractiveMapper.class);
        LLMService llmService = mock(LLMService.class);
        InteractiveSessionRecord session = interactiveSession();
        session.setUnderstandingStatus("READY");
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean storeHadTransaction = new AtomicBoolean(false);

        when(mapper.findSessionByTaskId("task-1")).thenReturn(Optional.of(session));
        when(mapper.listOptionsBySessionId("session-1")).thenReturn(List.of());
        // 输出故意不可解析，服务会走内置兜底卡；落库路径与正常解析完全相同
        when(llmService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "不是 JSON";
        });
        when(mapper.insertOption(any(CreativeIdeaOptionRecord.class))).thenAnswer(invocation -> {
            storeHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        CreatorInteractiveService service = new CreatorInteractiveService(
                mapper,
                mock(CreatorTaskService.class),
                llmService,
                new ObjectMapper(),
                mock(DocumentExtractionService.class),
                transactionTemplate(dataSource)
        );

        service.generateCreativeOptions("task-1", null);

        assertThat(modelCallHadTransaction).isFalse();
        assertThat(storeHadTransaction).isTrue();
    }

    @Test
    void suggestionShouldCallModelOutsideTransactionAndSaveInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicReference<CreatorSuggestionRecord> saved = new AtomicReference<>();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean saveHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(suggestionTask()));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(material("本视频文稿")));
        when(llmService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "{\"contentSummary\":\"摘要\"}";
        });
        when(suggestionMapper.upsert(any(CreatorSuggestionRecord.class))).thenAnswer(invocation -> {
            saveHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            saved.set(invocation.getArgument(0));
            return 1;
        });
        when(suggestionMapper.findBySuggestionId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));

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
                transactionTemplate(dataSource)
        );

        service.generateSuggestion(
                "task-1",
                new PrePublishAnalyzeRequest(null, null, null, null, "IGNORE_HISTORY", null));

        assertThat(modelCallHadTransaction).isFalse();
        assertThat(saveHadTransaction).isTrue();
    }

    @Test
    void reportShouldCallModelOutsideTransactionAndSaveInsideTransaction() throws Exception {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        CreatorCompetitorMapper competitorMapper = mock(CreatorCompetitorMapper.class);
        CreatorReportMapper reportMapper = mock(CreatorReportMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicReference<CreatorReportRecord> saved = new AtomicReference<>();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean saveHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(reportTask()));
        when(taskMapper.listTasksByUser(anyString(), anyInt())).thenReturn(List.of());
        when(feedbackMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(feedbackReport("反馈摘要")));
        when(competitorMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(competitorReport()));
        when(feedbackMapper.findMetricByTaskId("task-1")).thenReturn(Optional.of(metric()));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorReportAnalysisOutput.class)))
                .thenAnswer(invocation -> {
                    modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ObjectMapper().readValue(REPORT_OUTPUT_JSON, CreatorReportAnalysisOutput.class);
                });
        when(reportMapper.upsert(any(CreatorReportRecord.class))).thenAnswer(invocation -> {
            saveHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            saved.set(invocation.getArgument(0));
            return 1;
        });
        when(reportMapper.findByTaskId("task-1")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

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
                transactionTemplate(dataSource)
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null));

        assertThat(modelCallHadTransaction).isFalse();
        assertThat(saveHadTransaction).isTrue();
    }

    @Test
    void reportShouldRollbackWhenHistorySnapshotCannotBeSaved() throws Exception {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        CreatorCompetitorMapper competitorMapper = mock(CreatorCompetitorMapper.class);
        CreatorReportMapper reportMapper = mock(CreatorReportMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicReference<CreatorReportRecord> saved = new AtomicReference<>();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean saveHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(reportTask()));
        when(taskMapper.listTasksByUser(anyString(), anyInt())).thenReturn(List.of());
        when(feedbackMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(feedbackReport("反馈摘要")));
        when(competitorMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(competitorReport()));
        when(feedbackMapper.findMetricByTaskId("task-1")).thenReturn(Optional.of(metric()));
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorReportAnalysisOutput.class)))
                .thenAnswer(invocation -> {
                    modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ObjectMapper().readValue(REPORT_OUTPUT_JSON, CreatorReportAnalysisOutput.class);
                });
        when(reportMapper.upsert(any(CreatorReportRecord.class))).thenAnswer(invocation -> {
            saveHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            saved.set(invocation.getArgument(0));
            return 1;
        });
        when(reportMapper.findByTaskId("task-1")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

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
                transactionTemplate(dataSource)
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        when(reportMapper.insertHistory(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    throw new IllegalStateException("历史写入失败");
                });
        assertThatThrownBy(() -> service.analyze("task-1", new CreatorReportAnalyzeRequest(null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("历史写入失败");
        // 用真实事务管理器验证历史失败会回滚，不能留下没有历史的最新成功结果。
        verify(dataSource.getConnection()).rollback();
    }

    @Test
    void competitorShouldCallModelOutsideTransactionAndSaveInsideTransaction() {
        DataSource dataSource = mockDataSource();
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        CreatorCompetitorMapper competitorMapper = mock(CreatorCompetitorMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        LLMService llmService = mock(LLMService.class);
        AtomicReference<CreatorCompetitorReportRecord> saved = new AtomicReference<>();
        AtomicBoolean modelCallHadTransaction = new AtomicBoolean(true);
        AtomicBoolean saveHadTransaction = new AtomicBoolean(false);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(competitorTask()));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(material("本视频文稿")));
        when(feedbackMapper.findReportByTaskId("task-1")).thenReturn(Optional.of(feedbackReport("反馈摘要")));
        when(competitorMapper.findCompetitorVideoByTaskId("task-1")).thenReturn(Optional.of(competitorSample()));
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.empty());
        when(llmService.chatStructured(anyString(), anyString(), eq(CreatorCompetitorAnalysisOutput.class)))
                .thenAnswer(invocation -> {
                    modelCallHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ObjectMapper().readValue(COMPETITOR_OUTPUT_JSON, CreatorCompetitorAnalysisOutput.class);
                });
        when(competitorMapper.upsertReport(any(CreatorCompetitorReportRecord.class))).thenAnswer(invocation -> {
            saveHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            saved.set(invocation.getArgument(0));
            return 1;
        });
        when(competitorMapper.findReportByTaskId("task-1")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

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
                transactionTemplate(dataSource)
        , new CreatorPublishedVideoContextService(mock(CreatorBilibiliMapper.class), new ObjectMapper()));

        service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null));

        assertThat(modelCallHadTransaction).isFalse();
        assertThat(saveHadTransaction).isTrue();
    }

    private static final String TEXT_FEEDBACK = """
            ## 评论样例
            讲得很清楚，希望补充完整示例
            ## 弹幕样例
            [00:12]学到了
            """;

    private static final String REPORT_OUTPUT_JSON = """
            {"contentSummary":"本期内容总结","coreSellingPoints":["卖点1"],"titleDescriptionReview":{"titleConclusion":"标题合适","descriptionConclusion":"简介清楚","tagAndPartitionConclusion":"分区准确","riskReminder":"注意风险"},"audienceFeedbackSummary":"反馈不错","competitorComparison":{"benchmarkConclusion":"对标清楚","ownAdvantages":["优势"],"ownDisadvantages":["短板"],"differentiationStrategy":"做差异化"},"controversyAndMisunderstanding":[{"point":"争议点","impact":"中等","action":"继续解释"}],"nextActionSuggestions":[{"suggestion":"做下一期","reason":"观众想看","priority":"HIGH"}],"creatorPreferenceInsight":["偏好干货表达"],"overallConclusion":"适合继续做"}
            """;

    private static final String COMPETITOR_OUTPUT_JSON = """
            {"competitorSummary":"竞品更强调结果","competitorAdvantages":[{"advantage":"标题更直接","evidence":"样例标题都突出收益","lesson":"标题前置结果"}],"ownAdvantages":[{"advantage":"解释更细","evidence":"文稿结构完整"}],"ownDisadvantages":[{"disadvantage":"卖点不够前置","evidence":"标题偏平","risk":"点击弱"}],"gapAnalysis":[{"dimension":"标题","gap":"结果感弱","priority":"HIGH"}],"improvementSuggestions":[{"suggestion":"标题突出收益","reason":"竞品样例有效","action":"重写标题"}],"differentiationStrategy":"主打可复制步骤"}
            """;

    private DataSource mockDataSource() {
        DataSource dataSource = mock(DataSource.class);
        try {
            when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return dataSource;
    }

    private TransactionTemplate transactionTemplate(DataSource dataSource) {
        // 用真实事务管理器拿到真实的"当前是否存在活动事务"，这样断言才有意义
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private PromptService promptServiceMirroringVariables() {
        PromptService promptService = mock(PromptService.class);
        when(promptService.get(anyString())).thenReturn("系统提示词");
        when(promptService.render(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.toString();
        });
        return promptService;
    }

    private CreatorTaskRecord feedbackTask() {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setUserId("default");
        task.setTaskName("反馈任务");
        task.setVideoType("知识科普");
        task.setStatus(CreatorTaskStatus.FEEDBACK_COLLECTING.name());
        return task;
    }

    private CreatorTaskRecord suggestionTask() {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setUserId("default");
        task.setTaskName("发布前优化任务");
        task.setVideoType("知识科普");
        task.setStatus(CreatorTaskStatus.DRAFT.name());
        return task;
    }

    private CreatorTaskRecord reportTask() {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setUserId("default");
        task.setTaskName("复盘任务");
        task.setVideoType("知识科普");
        task.setStatus(CreatorTaskStatus.COMPETITOR_ANALYZED.name());
        return task;
    }

    private CreatorTaskRecord competitorTask() {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setUserId("default");
        task.setTaskName("竞品分析任务");
        task.setVideoType("知识科普");
        task.setStatus(CreatorTaskStatus.FEEDBACK_ANALYZED.name());
        return task;
    }

    private InteractiveSessionRecord interactiveSession() {
        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setTaskId("task-1");
        session.setSessionId("session-1");
        session.setUserId("default");
        session.setIdea("想讲清楚一次线上故障是怎么定位的");
        session.setVideoType("知识科普");
        session.setUnderstandingStatus("NONE");
        session.setStatus("IDEA_INPUT");
        return session;
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

    private CreatorCompetitorReportRecord competitorReport() {
        CreatorCompetitorReportRecord record = new CreatorCompetitorReportRecord();
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

    private CreatorFeedbackRecord feedback() {
        CreatorFeedbackRecord feedback = new CreatorFeedbackRecord();
        feedback.setFeedbackId("feedback-1");
        feedback.setTaskId("task-1");
        feedback.setCommentSamples("讲得很清楚，希望补充完整示例");
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
