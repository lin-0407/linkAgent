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
import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.llm.LLMService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorCompetitorServiceTest {

    @Test
    void shouldSaveCompetitorVideo() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                competitorMapper,
                new FixedLlmService("{}"),
                new ObjectMapper());

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
    }

    @Test
    void shouldAnalyzeCompetitorVideo() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();
        taskMapper.materials = List.of(createMaterialRecord());

        FakeCreatorCompetitorMapper competitorMapper = new FakeCreatorCompetitorMapper();
        competitorMapper.competitorVideoRecord = createCompetitorVideoRecord();

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                competitorMapper,
                new FixedLlmService("""
                        {"competitorSummary":"竞品更强调结果","competitorAdvantages":[{"advantage":"标题更直接","evidence":"样例标题都突出收益","lesson":"标题前置结果"}],"ownAdvantages":[{"advantage":"解释更细","evidence":"文稿结构完整"}],"ownDisadvantages":[{"disadvantage":"卖点不够前置","evidence":"标题偏平","risk":"点击弱"}],"gapAnalysis":[{"dimension":"标题","gap":"结果感弱","priority":"HIGH"}],"improvementSuggestions":[{"suggestion":"标题突出收益","reason":"竞品样例有效","action":"重写标题"}],"differentiationStrategy":"主打可复制步骤"}
                        """),
                new ObjectMapper());

        CreatorCompetitorReportResponse response = service.analyze(
                "task-1",
                new CreatorCompetitorAnalyzeRequest(null, "重点对比标题", null)
        );

        assertThat(response.competitorSummary()).isEqualTo("竞品更强调结果");
        assertThat(response.ownDisadvantages()).contains("卖点不够前置");
        assertThat(response.parseStatus()).isEqualTo("PARSED");
        assertThat(taskMapper.updatedStatus).isEqualTo(CreatorTaskStatus.COMPETITOR_ANALYZED.name());
    }

    @Test
    void shouldFailWhenSampleMissing() {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper();
        taskMapper.taskRecord = createTaskRecord();

        CreatorCompetitorService service = new CreatorCompetitorService(
                taskMapper,
                new FakeCreatorSuggestionMapper(),
                new FakeCreatorFeedbackMapper(),
                new FakeCreatorCompetitorMapper(),
                new FixedLlmService("{}"),
                new ObjectMapper());

        assertThatThrownBy(() -> service.analyze("task-1", new CreatorCompetitorAnalyzeRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("请先提交同类型竞品视频");
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
        public int updateTaskStatus(String taskId, String status) {
            this.updatedStatus = status;
            return 1;
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
    }

    private static class FakeCreatorFeedbackMapper implements CreatorFeedbackMapper {

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
            return Optional.empty();
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
