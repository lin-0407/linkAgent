package com.link.linkagent.creator.bilibili.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoResponse;
import com.link.linkagent.creator.bilibili.model.BindAccountRequest;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingResponse;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B站接口服务层的空值回归测试。
 * <p>
 * 控制器本身只负责参数校验和转发；会导致 NPE 的业务组合在 Service 层。
 * 因此这里用 Mock Mapper 覆盖 B站模块全部六个对外服务入口，不依赖真实 MySQL 或 B站接口。
 */
class CreatorBilibiliServiceTest {

    /**
     * 同步接口当前没有真实 B站错误时，lastError 的业务语义就是 null。
     * Map.of 不接受 null，本用例在修复前会稳定复现线上 NPE。
     */
    @Test
    void shouldReturnNullableLastErrorWhenSyncSucceedsWithoutError() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account("default", "27058248")));
        CreatorBilibiliService service = new CreatorBilibiliService(bilibiliMapper, taskMapper);

        Map<String, Object> result = service.syncVideos("default");

        assertThat(result).containsKey("lastError");
        assertThat(result.get("lastError")).isNull();
        assertThat(result.get("bilibiliUid")).isEqualTo("27058248");
    }

    /** POST /accounts：首次绑定时应创建记录并返回完整账号信息。 */
    @Test
    void shouldBindAccountWhenNoExistingAccount() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        BilibiliAccountRecord account = account("default", "27058248");
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.empty(), Optional.of(account));
        CreatorBilibiliService service = service(bilibiliMapper, mock(CreatorTaskMapper.class));

        BilibiliAccountResponse result = service.bindAccount(
                new BindAccountRequest("default", "27058248"));

        assertThat(result.userId()).isEqualTo("default");
        assertThat(result.bilibiliUid()).isEqualTo("27058248");
        verify(bilibiliMapper).insertAccount(org.mockito.ArgumentMatchers.any(BilibiliAccountRecord.class));
    }

    /** GET /accounts/{userId}：已绑定账号应能转换为前端响应。 */
    @Test
    void shouldGetBoundAccount() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account("default", "27058248")));
        CreatorBilibiliService service = service(bilibiliMapper, mock(CreatorTaskMapper.class));

        BilibiliAccountResponse result = service.getAccount("default");

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo("account-1");
    }

    /** GET /accounts/{uid}/linked-videos：有绑定和缓存视频时应组装视频卡片。 */
    @Test
    void shouldReturnLinkedVideosWithoutNullPointerException() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        TaskVideoBindingRecord binding = binding("task-1", "default", "27058248", "BV1xx411c7mD", "BOUND");
        CreatorTaskRecord task = task("task-1", "测试任务");
        BilibiliVideoRecord video = video("27058248", "BV1xx411c7mD");
        when(bilibiliMapper.listBindingsByUid("27058248")).thenReturn(List.of(binding));
        when(bilibiliMapper.findTasksByTaskIds(List.of("task-1"))).thenReturn(List.of(task));
        when(bilibiliMapper.findVideoByBvidAndUid("BV1xx411c7mD", "27058248"))
                .thenReturn(Optional.of(video));
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper);

        List<BilibiliVideoResponse> result = service.getLinkedVideos("27058248", "default");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().taskName()).isEqualTo("测试任务");
        assertThat(result.getFirst().bvid()).isEqualTo("BV1xx411c7mD");
    }

    /** POST /tasks/{taskId}/video-binding：新 BV 绑定应在写入后返回当前绑定。 */
    @Test
    void shouldBindBvToExistingTask() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(
                        binding("task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY")));
        when(bilibiliMapper.findBindingsByBvid("BV1xx411c7mD")).thenReturn(List.of());
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper);

        TaskVideoBindingResponse result = service.bindBvToTask(
                "task-1", new BindBvRequest("default", "27058248", "BV1xx411c7mD"));

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.bindingStatus()).isEqualTo("WAITING_VERIFY");
        verify(bilibiliMapper).insertBinding(org.mockito.ArgumentMatchers.any(TaskVideoBindingRecord.class));
    }

    /** GET /tasks/{taskId}/video-binding：已有绑定应直接转换为前端响应。 */
    @Test
    void shouldGetTaskVideoBinding() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.of(binding(
                        "task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY")));
        CreatorBilibiliService service = service(bilibiliMapper, mock(CreatorTaskMapper.class));

        TaskVideoBindingResponse result = service.getTaskBinding("task-1");

        assertThat(result).isNotNull();
        assertThat(result.bvid()).isEqualTo("BV1xx411c7mD");
    }

    private BilibiliAccountRecord account(String userId, String bilibiliUid) {
        return new BilibiliAccountRecord(
                1L,
                "account-1",
                userId,
                bilibiliUid,
                null,
                "ACTIVE",
                null,
                null,
                null,
                null
        );
    }

    private TaskVideoBindingRecord binding(String taskId, String userId, String bilibiliUid,
                                           String bvid, String bindingStatus) {
        return new TaskVideoBindingRecord(
                1L,
                "binding-1",
                taskId,
                userId,
                bilibiliUid,
                bvid,
                bindingStatus,
                null,
                LocalDateTime.of(2026, 7, 14, 10, 0),
                LocalDateTime.of(2026, 7, 14, 10, 0)
        );
    }

    private CreatorTaskRecord task(String taskId, String taskName) {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setId(1L);
        task.setTaskId(taskId);
        task.setUserId("default");
        task.setTaskName(taskName);
        task.setVideoType("项目展示");
        task.setStatus("PRE_PUBLISH_CONFIRMED");
        return task;
    }

    private BilibiliVideoRecord video(String bilibiliUid, String bvid) {
        return new BilibiliVideoRecord(
                1L,
                "video-1",
                bilibiliUid,
                bvid,
                1L,
                "测试视频",
                null,
                LocalDateTime.of(2026, 7, 14, 9, 0),
                100L,
                10L,
                1L,
                2L,
                3L,
                "SYNCED",
                LocalDateTime.of(2026, 7, 14, 10, 0),
                "{}",
                LocalDateTime.of(2026, 7, 14, 9, 0),
                LocalDateTime.of(2026, 7, 14, 10, 0)
        );
    }

    private CreatorBilibiliService service(CreatorBilibiliMapper bilibiliMapper,
                                            CreatorTaskMapper taskMapper) {
        return new CreatorBilibiliService(bilibiliMapper, taskMapper);
    }
}
