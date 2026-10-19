package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.llm.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段二写入决策测试：模型只给动作，能不能落库由代码校验说了算（设计文档 C13 / D14）。
 */
class LongTermMemoryDecisionMakerTest {

    private static final String EXISTING_CONSTRAINT = "不要引入多租户";
    private static final String EXISTING_STYLE = "用户偏好简洁解释";

    private LLMService llmService;
    private LongTermMemory longTermMemory;
    private final AtomicLong idSequence = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        longTermMemory = mock(LongTermMemory.class);
        when(longTermMemory.save(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    LongTermMemoryRecord record = new LongTermMemoryRecord();
                    record.setId(idSequence.incrementAndGet());
                    record.setUserId(invocation.getArgument(0));
                    record.setMemoryKey(invocation.getArgument(1));
                    record.setContent(invocation.getArgument(2));
                    record.setImportance(invocation.getArgument(4));
                    return Optional.of(record);
                });
    }

    @Test
    void shouldAddAllCandidatesWhenNoExistingMemory() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50))).thenReturn(List.of());

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1", List.of(
                new LongTermMemoryCandidate(true, "user.preference.example_language", "示例默认用 Java", 88),
                new LongTermMemoryCandidate(true, "project.profile.summary", "LinkAgent 是个人工作台", 86)));

        // 库里一条记忆都没有时没有冲突可判，直接全部新增：省掉一次模型调用，结论也不会不同
        verify(llmService, never()).chat(anyString(), anyString());
        verify(longTermMemory).save(eq("default"), eq("user.preference.example_language"), eq("示例默认用 Java"), eq("session-1"), eq(88));
        assertThat(outcome.added()).isEqualTo(2);
    }

    @Test
    void shouldSaveDifferentEventsWhenMemoryIsEmpty() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50))).thenReturn(List.of());

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1", List.of(
                new LongTermMemoryCandidate(true, "user.event", "首次保养汽车后 GPS 故障", 88),
                new LongTermMemoryCandidate(true, "user.event", "2 月先维护自行车，后维护汽车", 86)));

        // 空库直写必须与抽取器一致，否则两条同类事件会在最后一步只存一条。
        assertThat(outcome.added()).isEqualTo(2);
        verify(longTermMemory).save("default", "user.event", "首次保养汽车后 GPS 故障", "session-1", 88);
        verify(longTermMemory).save("default", "user.event", "2 月先维护自行车，后维护汽车", "session-1", 86);
    }

    @Test
    void shouldAddLaterEventWithoutReplacingEarlierEvent() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.event", "2 月先维护自行车", 84)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"ADD","id":0,"memory":"2 月后来维护汽车","oldMemory":"","importance":86}]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.event", "2 月后来维护汽车", 86)));

        // 后一件事不是前一件事的新版本；模型判 ADD 后代码应保留原事件。
        assertThat(outcome.added()).isEqualTo(1);
        verify(longTermMemory).save("default", "user.event", "2 月后来维护汽车", "session-1", 86);
        verify(longTermMemory, never()).update(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void shouldUpdateExistingMemoryKeepingItsId() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"UPDATE","id":27,"memory":"用户偏好简洁解释并配一句例子","oldMemory":"用户偏好简洁解释","importance":85,"reason":"同一事实的新版本"}]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.preference.explanation_style", "用户偏好简洁解释并配一句例子", 85)));

        // UPDATE 必须沿用原 id（D8）：换了说法不代表换了一条记忆
        verify(longTermMemory).update("default", 27L, "用户偏好简洁解释并配一句例子", 85);
        assertThat(outcome.updated()).isEqualTo(1);
    }

    @Test
    void shouldRejectUpdateWhenOldMemoryDoesNotMatch() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"UPDATE","id":27,"memory":"用户喜欢长篇报告","oldMemory":"用户其实喜欢长篇报告","importance":85}]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.preference.explanation_style", "用户喜欢长篇报告", 85)));

        // oldMemory 对不上说明模型在编 id，宁可少写一条也不能让它改掉别人的记忆
        verify(longTermMemory, never()).update(anyString(), any(), anyString(), anyInt());
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void shouldRejectDecisionReferencingUnknownId() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"DELETE","id":999,"memory":"","oldMemory":"用户偏好简洁解释"}]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.preference.explanation_style", "用户偏好简洁解释", 70)));

        verify(longTermMemory, never()).markDeleted(anyString(), any(), any());
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void shouldRejectOverlongContent() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50))).thenReturn(List.of());
        String overlong = "长".repeat(LongTermMemoryDecisionMaker.MAX_CONTENT_LENGTH + 1);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply(
                "default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.profile.summary", overlong, 90)));

        verify(longTermMemory, never()).save(anyString(), anyString(), anyString(), anyString(), anyInt());
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void shouldCapWritesPerRound() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[
                  {"action":"ADD","id":0,"memory":"用户在做 AI 应用实习","importance":85},
                  {"action":"ADD","id":0,"memory":"用户每周更新一期视频","importance":82}
                ]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker(1, 50).decideAndApply("default", "session-1", List.of(
                new LongTermMemoryCandidate(true, "user.profile.summary", "用户在做 AI 应用实习", 85),
                new LongTermMemoryCandidate(true, "project.profile.summary", "用户每周更新一期视频", 82)));

        // 单轮写入上限（C14）：超出的动作被拒绝并记日志，不是静默丢掉
        verify(longTermMemory).save(eq("default"), eq("user.profile.summary"), eq("用户在做 AI 应用实习"), eq("session-1"), eq(85));
        assertThat(outcome.added()).isEqualTo(1);
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void shouldSupersedeDeletedMemoryWithNewSameKeyRecord() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(30L, "project.constraint.summary", EXISTING_CONSTRAINT, 86)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[
                  {"action":"DELETE","id":30,"memory":"","oldMemory":"不要引入多租户","reason":"约束升级"},
                  {"action":"ADD","id":0,"memory":"不要引入注册登录与多租户","importance":90,"reason":"约束升级后的新说法"}
                ]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "project.constraint.summary", "不要引入注册登录与多租户", 90)));

        // 同 key 的新增记录 id 回填到被取代记录的 superseded_by：这就是 D15 要区分的「被新事实取代」
        verify(longTermMemory).markDeleted("default", 30L, 101L);
        assertThat(outcome.deleted()).isEqualTo(1);
        assertThat(outcome.added()).isEqualTo(1);
    }

    @Test
    void shouldThrowWhenDecisionsArrayIsMissing() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"result\":\"ok\"}");

        // 决策输出不可解析时必须抛异常让消费者重试；按「没有动作」处理会推游标，那批候选就永久丢了
        assertThatThrownBy(() -> decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.profile.summary", "用户在做实习", 90))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decisions");
    }

    @Test
    void shouldIgnoreNoneDecisions() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27L, "user.preference.explanation_style", EXISTING_STYLE, 70)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"NONE","id":0,"memory":"","oldMemory":"","reason":"已有记忆已覆盖"}]}
                """);

        LongTermMemoryDecisionMaker.WriteOutcome outcome = decisionMaker().decideAndApply("default", "session-1",
                List.of(new LongTermMemoryCandidate(true, "user.preference.explanation_style", EXISTING_STYLE, 70)));

        verify(longTermMemory, never()).save(anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(longTermMemory, never()).update(anyString(), any(), anyString(), anyInt());
        assertThat(outcome.skipped()).isEqualTo(1);
    }

    @Test
    void shouldPreserveNewEventDateEvenWhenModelSaysNone() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "3 月正在与 Rachel 合作找房", 75)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"NONE","candidateId":1,"id":27,"memory":"","reason":"已经记住合作"}]}
                """);
        String fact = "用户自 2022/2/15 起与 Rachel 合作找房";
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", fact, 85)));
        assertThat(outcome.added()).isEqualTo(1);
        verify(longTermMemory).save("default", "user.event", fact, "session-1", 85);
    }

    @Test
    void shouldRecognizeSameExplicitDateAcrossFormatsWithoutDuplicatingEvent() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "用户于 2022 年 2 月 15 日开始合作", 85)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"NONE","candidateId":1,"id":27,"memory":""}]}
                """);
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", "开始合作的日期为 February 15, 2022", 85)));
        assertThat(outcome.skipped()).isEqualTo(1);
        verify(longTermMemory, never()).save(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldKeepOldEventDateWhenProposedUpdateOnlyContainsGeneralStatus() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "2022-02-15 开始合作", 85)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"UPDATE","candidateId":1,"id":27,"oldMemory":"2022-02-15 开始合作","memory":"3 月正在合作"}]}
                """);
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", "3 月正在合作", 70)));
        assertThat(outcome.added()).isEqualTo(1);
        verify(longTermMemory, never()).update(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void shouldAllowExplicitDateCorrectionFromCandidate() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "2022-02-15 开始合作", 85)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"UPDATE","candidateId":1,"id":27,"oldMemory":"2022-02-15 开始合作","memory":"用户更正为 2022-02-16 开始合作"}]}
                """);
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", "用户更正为 2022-02-16 开始合作", 85)));
        assertThat(outcome.updated()).isEqualTo(1);
        verify(longTermMemory).update("default", 27L, "用户更正为 2022-02-16 开始合作", 85);
    }

    @Test
    void shouldPreserveDatedCandidateOmittedFromModelDecisions() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "正在合作", 75)));
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"decisions\":[]}");
        String fact = "2/15 开始合作，3/1 找到房子";
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", fact, 85)));
        assertThat(outcome.added()).isEqualTo(1);
        verify(longTermMemory).save("default", "user.event", fact, "session-1", 85);
    }

    @Test
    void shouldKeepEventCategoryWhenModelRewritesAddWithCandidateId() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.profile.summary", "用户正在购房", 75)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"ADD","candidateId":2,"id":0,"memory":"2022-02-15 开始与 Rachel 合作","importance":85}]}
                """);
        decisionMaker().decideAndApply("default", "session-1", List.of(
                new LongTermMemoryCandidate(true, "user.profile.summary", "用户在加州工作", 75),
                new LongTermMemoryCandidate(true, "user.event", "用户于 2022/2/15 开始与 Rachel 合作", 85)));
        verify(longTermMemory).save("default", "user.event", "2022-02-15 开始与 Rachel 合作", "session-1", 85);
    }

    @Test
    void shouldPreserveBothCandidateDatesWhenProposedAddDropsOne() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "正在合作找房", 75)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"ADD","candidateId":1,"id":0,"memory":"3/1 找到房子","importance":85}]}
                """);
        String fact = "2/15 开始合作，3/1 找到房子";
        decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true, "user.event", fact, 85)));
        verify(longTermMemory).save("default", "user.event", fact, "session-1", 85);
    }

    @Test
    void shouldFailInsteadOfSilentlyDiscardingDatedEventBeyondWriteLimit() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50))).thenReturn(List.of());
        assertThatThrownBy(() -> decisionMaker(1, 50).decideAndApply("default", "session-1", List.of(
                new LongTermMemoryCandidate(true, "user.event", "2/15 开始合作", 85),
                new LongTermMemoryCandidate(true, "user.event", "3/1 找到房子", 85))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("游标必须保留");
    }

    @Test
    void shouldNotDuplicateEventForGeneralRestatementWithDateBoundary() {
        when(longTermMemory.retrieveForDecision(eq("default"), anyList(), eq(50)))
                .thenReturn(List.of(existing(27, "user.event", "2 月中旬将自行车送去维修", 85)));
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"decisions":[{"action":"NONE","candidateId":1,"id":27,"memory":"","reason":"已有明确维修时间，候选是笼统重述"}]}
                """);
        var outcome = decisionMaker().decideAndApply("default", "session-1", List.of(new LongTermMemoryCandidate(true,
                "user.event", "用户近期（2023-03-10 之前不久）将自行车送去维修", 45)));
        assertThat(outcome.skipped()).isEqualTo(1);
        verify(longTermMemory, never()).save(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    private LongTermMemoryDecisionMaker decisionMaker() {
        return new LongTermMemoryDecisionMaker(llmService, new RenderingPromptService(), longTermMemory, new ObjectMapper());
    }

    private LongTermMemoryDecisionMaker decisionMaker(int maxWrites, int existingLimit) {
        return new LongTermMemoryDecisionMaker(llmService, new RenderingPromptService(), longTermMemory,
                new ObjectMapper(), maxWrites, existingLimit);
    }

    private LongTermMemoryRecord existing(long id, String memoryKey, String content, int importance) {
        LongTermMemoryRecord record = new LongTermMemoryRecord();
        record.setId(id);
        record.setUserId("default");
        record.setMemoryKey(memoryKey);
        record.setContent(content);
        record.setImportance(importance);
        return record;
    }
}
