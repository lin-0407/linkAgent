package com.link.linkagent.creator.bilibili.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncItem;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoVerificationResult;
import com.link.linkagent.creator.bilibili.model.BindAccountRequest;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.PostPublishReadinessResponse;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingResponse;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B站账号、视频同步和任务绑定服务层测试。
 * <p>
 * 控制器只负责参数校验和转发；同步事务、归属校验和幂等绑定都在 Service 层验证，
 * 测试不依赖真实 MySQL、B站接口或 Python 进程。
 */
class CreatorBilibiliServiceTest {

    /** 同步入口应把当前用户绑定的 BV 交给 Provider 定向校验，并返回持久化结果。 */
    @Test
    void shouldSyncAccountVideosAndReturnStructuredResult() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        BilibiliVideoSyncProvider syncProvider = mock(BilibiliVideoSyncProvider.class);
        BilibiliVideoSyncPersistenceService persistenceService = mock(BilibiliVideoSyncPersistenceService.class);
        BilibiliAccountRecord account = account("default", "27058248");
        TaskVideoBindingRecord binding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY");
        BilibiliVideoSyncPayload payload = syncPayload("27058248");
        BilibiliVideoSyncResponse expected = new BilibiliVideoSyncResponse(
                "27058248", "SUCCESS", 1, 1, 0, null, List.of(), false, "同步完成");

        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account));
        when(bilibiliMapper.listBindingsByUserId("default")).thenReturn(List.of(binding));
        when(syncProvider.fetch("27058248", List.of("BV1xx411c7mD"))).thenReturn(payload);
        when(persistenceService.persist(account, payload, List.of(binding))).thenReturn(expected);
        CreatorBilibiliService service = new CreatorBilibiliService(
                bilibiliMapper, taskMapper, syncProvider, persistenceService,
                mock(CreatorMediaWorkflowGateService.class));

        BilibiliVideoSyncResponse result = service.syncVideos("default");

        assertThat(result.lastError()).isNull();
        assertThat(result.bilibiliUid()).isEqualTo("27058248");
        assertThat(result.linkedCount()).isEqualTo(1);
        verify(syncProvider).fetch("27058248", List.of("BV1xx411c7mD"));
    }

    /** 同步持久化应幂等写入视频缓存，并把归属正确的待校验绑定推进为 BOUND。 */
    @Test
    void shouldPersistSyncedVideoAndVerifyBinding() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        BilibiliVideoSyncPersistenceService persistenceService =
                new BilibiliVideoSyncPersistenceService(bilibiliMapper, mediaWorkflowGateService);
        BilibiliAccountRecord account = account("default", "27058248");
        TaskVideoBindingRecord binding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY");

        BilibiliVideoSyncResponse result = persistenceService.persist(
                account,
                syncPayload("27058248"),
                List.of(binding)
        );

        assertThat(result.syncedCount()).isEqualTo(1);
        assertThat(result.linkedCount()).isEqualTo(1);
        assertThat(result.anomalyCount()).isZero();
        verify(bilibiliMapper).insertVideo(org.mockito.ArgumentMatchers.any(BilibiliVideoRecord.class));
        verify(bilibiliMapper).updateBindingStatus(
                "binding-1", "BOUND", "BV归属校验通过");
        verify(bilibiliMapper).updateAccountSyncResult(
                org.mockito.ArgumentMatchers.eq("account-1"),
                org.mockito.ArgumentMatchers.eq("测试账号"),
                org.mockito.ArgumentMatchers.eq("https://i0.hdslb.com/bfs/face/avatar.jpg"),
                org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.isNull());
        verify(mediaWorkflowGateService).ensureReadyForPostPublish("task-1", "default", "BV绑定");
    }

    /** 存量待校验绑定可随账号同步校验，但不能因此绕过成片媒体探测推进为 BOUND。 */
    @Test
    void shouldKeepLegacyBindingUnchangedWhenMediaGateRejectsSync() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        BilibiliVideoSyncPersistenceService persistenceService =
                new BilibiliVideoSyncPersistenceService(bilibiliMapper, mediaWorkflowGateService);
        BilibiliAccountRecord account = account("default", "27058248");
        TaskVideoBindingRecord binding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "成片尚未通过媒体探测"))
                .when(mediaWorkflowGateService)
                .ensureReadyForPostPublish("task-1", "default", "BV绑定");

        BilibiliVideoSyncResponse result = persistenceService.persist(
                account,
                syncPayload("27058248"),
                List.of(binding)
        );

        assertThat(result.syncedCount()).isEqualTo(1);
        assertThat(result.linkedCount()).isZero();
        assertThat(result.warnings()).contains("存在尚未完成成片媒体探测的任务，已跳过其BV绑定通过状态更新");
        verify(bilibiliMapper, never()).updateBindingStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** 负向归属事实不会放行发布后流程，即使媒体未就绪也应落库，避免旧绑定永久停在待校验。 */
    @Test
    void shouldPersistNegativeBindingDecisionWithoutMediaGate() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        BilibiliVideoSyncPersistenceService persistenceService =
                new BilibiliVideoSyncPersistenceService(bilibiliMapper, mediaWorkflowGateService);
        BilibiliAccountRecord account = account("default", "27058248");
        TaskVideoBindingRecord binding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY");
        BilibiliVideoVerificationResult verification = new BilibiliVideoVerificationResult(
                "BV1xx411c7mD",
                "UID_MISMATCH",
                "99999999",
                "BV不属于当前绑定UID"
        );
        BilibiliVideoSyncPayload payload = new BilibiliVideoSyncPayload(
                "27058248",
                "测试账号",
                "https://i0.hdslb.com/bfs/face/avatar.jpg",
                false,
                false,
                List.of(),
                List.of(verification),
                List.of()
        );

        BilibiliVideoSyncResponse result = persistenceService.persist(account, payload, List.of(binding));

        assertThat(result.anomalyCount()).isEqualTo(1);
        verify(bilibiliMapper).updateBindingStatus(
                "binding-1", "UID_MISMATCH", "BV不属于当前绑定UID");
        verifyNoInteractions(mediaWorkflowGateService);
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
        assertThat(result.avatarUrl()).isEqualTo("https://i0.hdslb.com/bfs/face/avatar.jpg");
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

    /** GET 发布后就绪状态应使用任务真实归属，并原样返回门禁的阻塞原因。 */
    @Test
    void shouldReturnPostPublishReadinessForTaskOwner() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        when(taskMapper.findTaskByTaskId("task-1"))
                .thenReturn(Optional.of(task("task-1", "测试任务")));
        when(mediaWorkflowGateService.inspectPostPublishReadiness("task-1", "default", "BV绑定"))
                .thenReturn(new CreatorMediaWorkflowGateService.PostPublishReadiness(
                        false,
                        "请先完成发布前试映，才能进入BV绑定阶段。"
                ));
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper, mediaWorkflowGateService);

        PostPublishReadinessResponse result = service.getPostPublishReadiness("task-1");

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.ready()).isFalse();
        assertThat(result.blockingReason()).contains("发布前试映");
        verify(mediaWorkflowGateService)
                .inspectPostPublishReadiness("task-1", "default", "BV绑定");
    }

    /** POST /tasks/{taskId}/video-binding：新 BV 绑定应在写入后返回当前绑定。 */
    @Test
    void shouldBindBvToExistingTask() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account("default", "27058248")));
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(
                        binding("task-1", "default", "27058248", "BV1xx411c7mD", "WAITING_VERIFY")));
        when(bilibiliMapper.findBindingsByBvid("BV1xx411c7mD")).thenReturn(List.of());
        when(bilibiliMapper.findVideoByBvidAndUid("BV1xx411c7mD", "27058248"))
                .thenReturn(Optional.empty());
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper);

        TaskVideoBindingResponse result = service.bindBvToTask(
                "task-1", new BindBvRequest("default", "27058248", "BV1xx411c7mD"));

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.bindingStatus()).isEqualTo("WAITING_VERIFY");
        verify(bilibiliMapper).insertBinding(org.mockito.ArgumentMatchers.any(TaskVideoBindingRecord.class));
    }

    /** 新建 BV 绑定必须先通过任务级媒体门禁，不能只依赖前端导航禁用。 */
    @Test
    void shouldRejectNewBvBindingWhenMediaGateRejects() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.empty());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "成片尚未通过媒体探测"))
                .when(mediaWorkflowGateService)
                .ensureReadyForPostPublish("task-1", "default", "BV绑定");
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper, mediaWorkflowGateService);

        assertThatThrownBy(() -> service.bindBvToTask(
                "task-1",
                new BindBvRequest("default", "27058248", "BV1xx411c7mD")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(bilibiliMapper, never()).insertBinding(org.mockito.ArgumentMatchers.any(TaskVideoBindingRecord.class));
    }

    /** 修正异常绑定同样必须先通过媒体门禁，拒绝后不能提前更新原绑定。 */
    @Test
    void shouldRejectNonBoundBindingRepairWhenMediaGateRejects() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(
                binding("task-1", "default", "27058248", "BV1xx411c7mD", "UID_MISMATCH")));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "发布前试映尚未完成"))
                .when(mediaWorkflowGateService)
                .ensureReadyForPostPublish("task-1", "default", "BV绑定");
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper, mediaWorkflowGateService);

        assertThatThrownBy(() -> service.bindBvToTask(
                "task-1",
                new BindBvRequest("default", "27058248", "BV1yy511c7eF")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(bilibiliMapper, never()).updateBindingDetails(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
    }

    /** 非 BOUND 绑定允许重新填写 BV，并在缓存可信时直接恢复为 BOUND。 */
    @Test
    void shouldRepairNonBoundBinding() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        TaskVideoBindingRecord oldBinding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "UID_MISMATCH");
        TaskVideoBindingRecord updatedBinding = binding(
                "task-1", "default", "27058248", "BV1yy511c7eF", "BOUND");

        when(taskMapper.findTaskByTaskId("task-1"))
                .thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.of(oldBinding), Optional.of(updatedBinding));
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account("default", "27058248")));
        when(bilibiliMapper.findBindingsByBvid("BV1yy511c7eF"))
                .thenReturn(List.of());
        when(bilibiliMapper.findVideoByBvidAndUid("BV1yy511c7eF", "27058248"))
                .thenReturn(Optional.of(video("27058248", "BV1yy511c7eF")));
        when(bilibiliMapper.updateBindingDetails(
                "binding-1", "27058248", "BV1yy511c7eF", "BOUND",
                "视频已在当前UID的公开视频缓存中，绑定校验通过"))
                .thenReturn(1);
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper);

        TaskVideoBindingResponse result = service.bindBvToTask(
                "task-1", new BindBvRequest("default", "27058248", "BV1yy511c7eF"));

        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        assertThat(result.bvid()).isEqualTo("BV1yy511c7eF");
        verify(bilibiliMapper).updateBindingDetails(
                "binding-1", "27058248", "BV1yy511c7eF", "BOUND",
                "视频已在当前UID的公开视频缓存中，绑定校验通过");
    }

    /** 已校验通过的绑定必须保持只读，后续重复请求不能换成另一个 BV。 */
    @Test
    void shouldNotOverwriteBoundBinding() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        TaskVideoBindingRecord boundBinding = binding(
                "task-1", "default", "27058248", "BV1xx411c7mD", "BOUND");

        when(taskMapper.findTaskByTaskId("task-1"))
                .thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.of(boundBinding));
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper, mediaWorkflowGateService);

        TaskVideoBindingResponse result = service.bindBvToTask(
                "task-1", new BindBvRequest("default", "27058248", "BV1yy511c7eF"));

        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        assertThat(result.bvid()).isEqualTo("BV1xx411c7mD");
        verify(bilibiliMapper, never()).updateBindingDetails(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        verifyNoInteractions(mediaWorkflowGateService);
    }

    /** 已同步缓存能够证明 BV 归属时，绑定不应继续停留在 WAITING_VERIFY。 */
    @Test
    void shouldBindCachedVideoImmediately() {
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1", "测试任务")));
        when(bilibiliMapper.findBindingByTaskId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(
                        binding("task-1", "default", "27058248", "BV1xx411c7mD", "BOUND")));
        when(bilibiliMapper.findAccountByUserId("default"))
                .thenReturn(Optional.of(account("default", "27058248")));
        when(bilibiliMapper.findBindingsByBvid("BV1xx411c7mD")).thenReturn(List.of());
        when(bilibiliMapper.findVideoByBvidAndUid("BV1xx411c7mD", "27058248"))
                .thenReturn(Optional.of(video("27058248", "BV1xx411c7mD")));
        CreatorBilibiliService service = service(bilibiliMapper, taskMapper);

        TaskVideoBindingResponse result = service.bindBvToTask(
                "task-1", new BindBvRequest("default", "27058248", "BV1xx411c7mD"));

        assertThat(result.bindingStatus()).isEqualTo("BOUND");
        verify(bilibiliMapper).insertBinding(org.mockito.ArgumentMatchers.argThat(record ->
                "BOUND".equals(record.getBindingStatus())));
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
                "https://i0.hdslb.com/bfs/face/avatar.jpg",
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
        task.setStatus(CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
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
        return service(bilibiliMapper, taskMapper, mock(CreatorMediaWorkflowGateService.class));
    }

    private CreatorBilibiliService service(CreatorBilibiliMapper bilibiliMapper,
                                            CreatorTaskMapper taskMapper,
                                            CreatorMediaWorkflowGateService mediaWorkflowGateService) {
        return new CreatorBilibiliService(
                bilibiliMapper,
                taskMapper,
                mock(BilibiliVideoSyncProvider.class),
                mock(BilibiliVideoSyncPersistenceService.class),
                mediaWorkflowGateService
        );
    }

    private BilibiliVideoSyncPayload syncPayload(String bilibiliUid) {
        BilibiliVideoSyncItem video = new BilibiliVideoSyncItem(
                "BV1xx411c7mD",
                1L,
                "测试视频",
                "https://example.com/cover.jpg",
                1784010000L,
                100L,
                10L,
                1L,
                2L,
                3L,
                bilibiliUid,
                "{}"
        );
        BilibiliVideoVerificationResult verification = new BilibiliVideoVerificationResult(
                "BV1xx411c7mD",
                "FOUND",
                bilibiliUid,
                "BV归属校验通过"
        );
        return new BilibiliVideoSyncPayload(
                bilibiliUid,
                "测试账号",
                "https://i0.hdslb.com/bfs/face/avatar.jpg",
                false,
                false,
                List.of(video),
                List.of(verification),
                List.of()
        );
    }
}
