package com.link.linkagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 短期记忆序列化的纯单元测试（不需要真实 Redis / Docker）。
 *
 * <p>锁定的生产行为（2026-10 原生协议改造）：
 * <ol>
 *   <li>新格式 v2：{@code 消息id \t v2 \t 角色 \t toolCallId \t toolName \t toolArguments \t 正文}，
 *       7 列、第三列固定 v2 作为版本标记；null 字段写 {@code \\N} 占位；</li>
 *   <li>工具调用与结果必须能带 toolCallId/toolName/toolArguments 往返，否则重启后配对关系丢失；</li>
 *   <li>旧的三列（id/角色/正文）和两列（角色/正文）格式仍能读出来——Redis 历史列表不会被这次改造重写。</li>
 * </ol>
 *
 * <p>用 mock 的 StringRedisTemplate 承载列表读写：序列化/反序列化是纯字符串逻辑，不需要真实 Redis。
 * 真实 Redis 的集成用例在 {@link RedisShortTermMemoryStoreTest} 里（需要 Docker 或 TEST_REDIS_HOST）。
 */
class RedisShortTermMemoryStoreSerializationTest {

    private final List<String> rawValues = new ArrayList<>();
    private RedisShortTermMemoryStore store;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
            rawValues.add(invocation.getArgument(1));
            return 1L;
        });
        when(listOperations.range(anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> List.copyOf(rawValues));
        store = new RedisShortTermMemoryStore(redisTemplate);
    }

    @Test
    void shouldRoundTripNativeToolCallPairInV2Format() {
        MemoryMessage call = MemoryMessage.toolCall("AI", 7L, "call_1", "get_comments", "video=BV1xx,page=1");
        MemoryMessage result = MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL, 8L,
                "call_1", "get_comments", "共 42 条评论");

        store.append("session-tool", call);
        store.append("session-tool", result);

        // v2 七列：id / v2 / role / toolCallId / toolName / toolArguments / content
        String[] callFields = rawValues.get(0).split("\t", -1);
        assertThat(callFields).hasSize(7);
        assertThat(callFields[0]).isEqualTo("7");
        assertThat(callFields[1]).isEqualTo("v2");
        assertThat(callFields[2]).isEqualTo("AI");
        assertThat(callFields[3]).isEqualTo("call_1");
        assertThat(callFields[4]).isEqualTo("get_comments");
        assertThat(callFields[5]).isEqualTo("video=BV1xx,page=1");
        // 调用消息正文为空串（不是 null 占位），和结果消息的 \\N 区分开
        assertThat(callFields[6]).isEmpty();

        String[] resultFields = rawValues.get(1).split("\t", -1);
        assertThat(resultFields).hasSize(7);
        assertThat(resultFields[0]).isEqualTo("8");
        assertThat(resultFields[1]).isEqualTo("v2");
        assertThat(resultFields[2]).isEqualTo(ShortTermMemory.ROLE_TOOL);
        assertThat(resultFields[3]).isEqualTo("call_1");
        assertThat(resultFields[4]).isEqualTo("get_comments");
        assertThat(resultFields[5]).isEqualTo("\\N");
        assertThat(resultFields[6]).isEqualTo("共 42 条评论");

        // 往返后记录相等：工厂方法保证字段语义（isToolCall / isToolResult）不变
        List<MemoryMessage> messages = store.getRecentMessages("session-tool");
        assertThat(messages).containsExactly(call, result);
        assertThat(messages.get(0).isToolCall()).isTrue();
        assertThat(messages.get(0).toolArguments()).isEqualTo("video=BV1xx,page=1");
        assertThat(messages.get(0).promptText()).isEqualTo("ToolCall: get_comments(video=BV1xx,page=1)");
        assertThat(messages.get(1).isToolResult()).isTrue();
        assertThat(messages.get(1).toolArguments()).isNull();
        assertThat(messages.get(1).toolCallId()).isEqualTo(messages.get(0).toolCallId());
    }

    @Test
    void shouldWriteNullToolFieldsAsPlaceholderForNormalMessage() {
        store.append("session-normal", new MemoryMessage("Human", "你好", 3L));

        String[] fields = rawValues.getFirst().split("\t", -1);
        assertThat(fields).hasSize(7);
        assertThat(fields[0]).isEqualTo("3");
        assertThat(fields[1]).isEqualTo("v2");
        assertThat(fields[2]).isEqualTo("Human");
        assertThat(fields[3]).isEqualTo("\\N");
        assertThat(fields[4]).isEqualTo("\\N");
        assertThat(fields[5]).isEqualTo("\\N");
        assertThat(fields[6]).isEqualTo("你好");

        assertThat(store.getRecentMessages("session-normal"))
                .containsExactly(new MemoryMessage("Human", "你好", 3L));
    }

    @Test
    void shouldEscapeToolFieldsSoTabsAndNewlinesSurviveRoundTrip() {
        MemoryMessage call = MemoryMessage.toolCall("AI", 9L, "call_2", "echo",
                "line1\nline2\twith-tab\\slash");
        MemoryMessage result = MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL, 10L, "call_2", "echo",
                "结果第一行\n结果第二行\t带制表");

        store.append("session-escape", call);
        store.append("session-escape", result);

        // 正文里的制表符/换行必须先转义，否则会被当成列分隔符，读回来字段错位
        assertThat(rawValues.get(0).split("\t", -1)).hasSize(7);
        assertThat(rawValues.get(0)).doesNotContain("\n");
        assertThat(rawValues.get(1).split("\t", -1)).hasSize(7);
        assertThat(rawValues.get(1)).doesNotContain("\n");

        assertThat(store.getRecentMessages("session-escape")).containsExactly(call, result);
    }

    @Test
    void shouldReadLegacyThreeColumnAndTwoColumnValues() {
        // 改造前的两种旧格式直接躺在 Redis 列表里，这次改造不重写历史数据，必须还能读
        rawValues.add("42\tHuman\t旧格式带消息 id");
        rawValues.add("Human\t旧格式无消息 id");

        assertThat(store.getRecentMessages("session-legacy")).containsExactly(
                new MemoryMessage("Human", "旧格式带消息 id", 42L),
                new MemoryMessage("Human", "旧格式无消息 id"));
    }
}
