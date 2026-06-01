package com.link.linkagent.creator.evaluation.service;

import com.link.linkagent.creator.evaluation.mapper.CreatorEvaluationMapper;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorEvaluationServiceTest {

    @Test
    void shouldCreateCaseWithDefaultUserAndTrimInput() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        CreatorEvalCaseResponse response = service.createCase(new CreatorEvalCaseCreateRequest(
                null,
                " 标题评测样例 ",
                "PRE_PUBLISH",
                null,
                " 输入快照 ",
                " 期望要点 ",
                null
        ));

        assertThat(response.userId()).isEqualTo("default");
        assertThat(response.caseName()).isEqualTo("标题评测样例");
        assertThat(response.inputSnapshot()).isEqualTo("输入快照");
        assertThat(response.expectedPoints()).isEqualTo("期望要点");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldRecordResultAndCalculateTotalTokens() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "FEEDBACK", "task-from-case");
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        CreatorEvalResultResponse response = service.recordResult("case-1", createResultRequest(
                "FEEDBACK",
                "{\"feedbackSummary\":\"ok\"}",
                null,
                10,
                15
        ));

        assertThat(response.caseId()).isEqualTo("case-1");
        assertThat(response.taskId()).isEqualTo("task-from-case");
        assertThat(response.totalTokens()).isEqualTo(25);
        assertThat(response.runStatus()).isEqualTo("SUCCESS");
        assertThat(response.parseStatus()).isEqualTo("PARSED");
        assertThat(response.promptVersion()).isEqualTo("feedback-v1");
        assertThat(response.promptHash()).hasSize(64);
    }

    @Test
    void shouldComparePromptVersionsByEvaluationScores() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "FEEDBACK", "task-from-case");
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        service.recordResult("case-1", createResultRequest("FEEDBACK", "{\"feedbackSummary\":\"v1\"}", null, 10, 15));
        service.recordResult("case-1", createResultRequest("FEEDBACK", "{\"feedbackSummary\":\"v1-2\"}", null, 20, 25));

        var stats = service.comparePromptVersions("case-1");

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().promptVersion()).isEqualTo("feedback-v1");
        assertThat(stats.getFirst().resultCount()).isEqualTo(2);
        assertThat(stats.getFirst().successCount()).isEqualTo(2);
        assertThat(stats.getFirst().successRatePercent()).isEqualTo(100.0);
        assertThat(stats.getFirst().scoreSampleCount()).isEqualTo(2);
        assertThat(stats.getFirst().totalTokens()).isEqualTo(70L);
        assertThat(stats.getFirst().averagePromptTokens()).isEqualTo(15.0);
        assertThat(stats.getFirst().averageCompletionTokens()).isEqualTo(20.0);
        assertThat(stats.getFirst().averageTotalTokens()).isEqualTo(35.0);
        assertThat(stats.getFirst().averageScore()).isEqualTo(4.0);
        assertThat(stats.getFirst().averageAccuracyScore()).isEqualTo(4.0);
        assertThat(stats.getFirst().scoreStandardDeviation()).isEqualTo(0.0);
        assertThat(stats.getFirst().fullScoreCoverageRatePercent()).isEqualTo(100.0);
    }

    @Test
    void shouldComparePromptVersionsWithAllResultsInsteadOfRecentLimit() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "FEEDBACK", "task-from-case");
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        for (int index = 0; index < 101; index++) {
            service.recordResult("case-1", createResultRequest(
                    "FEEDBACK",
                    "{\"feedbackSummary\":\"ok\"}",
                    null,
                    10,
                    15
            ));
        }

        var stats = service.comparePromptVersions("case-1");

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().resultCount()).isEqualTo(101);
        assertThat(stats.getFirst().totalTokens()).isEqualTo(2525L);
    }

    @Test
    void shouldKeepTotalTokensNullWhenTokenPartIsMissing() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "FEEDBACK", "task-from-case");
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        CreatorEvalResultResponse response = service.recordResult("case-1", createResultRequest(
                "FEEDBACK",
                "{\"feedbackSummary\":\"ok\"}",
                null,
                10,
                null
        ));

        assertThat(response.promptTokens()).isEqualTo(10);
        assertThat(response.completionTokens()).isNull();
        assertThat(response.totalTokens()).isNull();
    }

    @Test
    void shouldNormalizeZeroTokensAsUnknownUsage() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "FEEDBACK", "task-from-case");
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        CreatorEvalResultResponse response = service.recordResult("case-1", createResultRequest(
                "FEEDBACK",
                "{\"feedbackSummary\":\"ok\"}",
                null,
                0,
                0
        ));

        assertThat(response.promptTokens()).isNull();
        assertThat(response.completionTokens()).isNull();
        assertThat(response.totalTokens()).isNull();
    }

    @Test
    void shouldRejectResultWithoutOutputOrFailureReason() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "REPORT", null);
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        assertThatThrownBy(() -> service.recordResult("case-1", createResultRequest(
                "REPORT",
                null,
                null,
                null,
                null
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("评测结果必须提供模型输出或失败原因");
    }

    @Test
    void shouldRejectStageMismatch() {
        FakeCreatorEvaluationMapper mapper = new FakeCreatorEvaluationMapper();
        CreatorEvalCaseRecord evalCase = createCaseRecord("case-1", "PRE_PUBLISH", null);
        mapper.cases.put(evalCase.getCaseId(), evalCase);
        CreatorEvaluationService service = new CreatorEvaluationService(mapper);

        assertThatThrownBy(() -> service.recordResult("case-1", createResultRequest(
                "FEEDBACK",
                "输出",
                null,
                null,
                null
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("评测结果阶段必须与评测用例阶段保持一致");
    }

    private CreatorEvalCaseRecord createCaseRecord(String caseId, String targetStage, String taskId) {
        CreatorEvalCaseRecord record = new CreatorEvalCaseRecord();
        record.setId(1L);
        record.setCaseId(caseId);
        record.setUserId("default");
        record.setCaseName("测试用例");
        record.setTargetStage(targetStage);
        record.setTaskId(taskId);
        record.setInputSnapshot("输入快照");
        record.setStatus("ACTIVE");
        return record;
    }

    private CreatorEvalResultCreateRequest createResultRequest(String targetStage,
                                                               String rawOutput,
                                                               String failureReason,
                                                               Integer promptTokens,
                                                               Integer completionTokens) {
        return new CreatorEvalResultCreateRequest(
                null,
                "session-1",
                targetStage,
                "qwen3",
                "feedback-v1",
                null,
                "system: 反馈分析\nuser: 评论样例",
                "输出摘要",
                rawOutput,
                1200L,
                promptTokens,
                completionTokens,
                null,
                failureReason,
                4,
                4,
                4,
                4,
                3,
                5,
                4,
                "人工备注"
        );
    }

    private static class FakeCreatorEvaluationMapper implements CreatorEvaluationMapper {

        private final Map<String, CreatorEvalCaseRecord> cases = new LinkedHashMap<>();
        private final Map<String, CreatorEvalResultRecord> results = new LinkedHashMap<>();

        @Override
        public int insertCase(CreatorEvalCaseRecord record) {
            record.setId((long) cases.size() + 1);
            cases.put(record.getCaseId(), record);
            return 1;
        }

        @Override
        public Optional<CreatorEvalCaseRecord> findCaseByCaseId(String caseId) {
            return Optional.ofNullable(cases.get(caseId));
        }

        @Override
        public List<CreatorEvalCaseRecord> listCases(String userId, String targetStage, int limit) {
            return cases.values()
                    .stream()
                    .filter(record -> userId == null || userId.equals(record.getUserId()))
                    .filter(record -> targetStage == null || targetStage.equals(record.getTargetStage()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int insertResult(CreatorEvalResultRecord record) {
            record.setId((long) results.size() + 1);
            results.put(record.getResultId(), record);
            return 1;
        }

        @Override
        public Optional<CreatorEvalResultRecord> findResultByResultId(String resultId) {
            return Optional.ofNullable(results.get(resultId));
        }

        @Override
        public List<CreatorEvalResultRecord> listResultsByCaseId(String caseId, int limit) {
            return results.values()
                    .stream()
                    .filter(record -> caseId.equals(record.getCaseId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CreatorEvalResultRecord> listAllResultsByCaseIdForStats(String caseId) {
            return results.values()
                    .stream()
                    .filter(record -> caseId.equals(record.getCaseId()))
                    .toList();
        }
    }
}
