package com.link.linkagent.creator.preference.service;

import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorPreferenceServiceTest {

    @Test
    void shouldSavePreferenceSnapshotFromParsedReport() {
        FakeCreatorPreferenceMapper mapper = new FakeCreatorPreferenceMapper();
        CreatorPreferenceService service = new CreatorPreferenceService(mapper);

        service.saveFromReport(createTaskRecord("user-1"), createReportRecord("[\"偏好标题前置结果\"]", "PARSED"));

        assertThat(mapper.savedRecord).isNotNull();
        assertThat(mapper.savedRecord.getUserId()).isEqualTo("user-1");
        assertThat(mapper.savedRecord.getSourceTaskId()).isEqualTo("task-1");
        assertThat(mapper.savedRecord.getSourceReportId()).isEqualTo("report-1");
        assertThat(mapper.savedRecord.getPreferenceContent()).contains("标题前置结果");
    }

    @Test
    void shouldSkipPreferenceWhenReportOnlyKeepsRawOutput() {
        FakeCreatorPreferenceMapper mapper = new FakeCreatorPreferenceMapper();
        CreatorPreferenceService service = new CreatorPreferenceService(mapper);

        service.saveFromReport(createTaskRecord("user-1"), createReportRecord("不是结构化偏好", "RAW_ONLY"));

        assertThat(mapper.savedRecord).isNull();
    }

    @Test
    void shouldBuildPromptContextFromRecentPreferences() {
        FakeCreatorPreferenceMapper mapper = new FakeCreatorPreferenceMapper();
        CreatorPreferenceRecord record = new CreatorPreferenceRecord();
        record.setPreferenceId("preference-1");
        record.setUserId("default");
        record.setSourceTaskId("task-1");
        record.setSourceReportId("report-1");
        record.setPreferenceContent("[\"偏好干货表达\"]");
        mapper.records.add(record);

        CreatorPreferenceService service = new CreatorPreferenceService(mapper);

        String promptContext = service.buildPromptContext(null);

        assertThat(promptContext).contains("来源任务 task-1");
        assertThat(promptContext).contains("偏好干货表达");
    }

    private CreatorTaskRecord createTaskRecord(String userId) {
        CreatorTaskRecord record = new CreatorTaskRecord();
        record.setTaskId("task-1");
        record.setUserId(userId);
        return record;
    }

    private CreatorReportRecord createReportRecord(String preferenceInsight, String parseStatus) {
        CreatorReportRecord record = new CreatorReportRecord();
        record.setReportId("report-1");
        record.setTaskId("task-1");
        record.setCreatorPreferenceInsight(preferenceInsight);
        record.setParseStatus(parseStatus);
        return record;
    }

    private static class FakeCreatorPreferenceMapper implements CreatorPreferenceMapper {

        private CreatorPreferenceRecord savedRecord;
        private final List<CreatorPreferenceRecord> records = new ArrayList<>();

        @Override
        public int upsert(CreatorPreferenceRecord record) {
            this.savedRecord = record;
            this.records.add(record);
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
