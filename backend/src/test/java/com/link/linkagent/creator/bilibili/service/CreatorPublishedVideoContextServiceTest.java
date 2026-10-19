package com.link.linkagent.creator.bilibili.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CreatorPublishedVideoContextServiceTest {

    @Test
    void versionedBindingMustNotInferMissingSnapshotFromLiveCache() {
        binding.setPublishedVersionId("version-1");
        assertThat(service.buildPrompt(task)).contains("快照缺失或不匹配")
                .doesNotContain(video.getTitle(), "正式标题：");
    }

    @Test
    void frozenCopyMustRemainAvailableWhenLiveCacheIsMissing() {
        binding.setPublishedVersionId("version-1");
        binding.setPublishedSnapshotJson("""
                {"versionId":"version-1","bvid":"BV16F411S72L","uid":"27058248",
                 "title":"冻结的标题","raw":{"desc":"冻结的简介"},"capturedAt":"2026-10-19T10:00:00"}
                """);
        when(mapper.findVideoByBvidAndUid(binding.getBvid(), binding.getBilibiliUid())).thenReturn(Optional.empty());
        assertThat(service.buildPrompt(task)).contains("冻结的标题", "冻结的简介", "实际发布版本：version-1", "播放量：未提供");
        binding.setBindingStatus("UID_MISMATCH");
        assertThat(service.buildPrompt(task)).contains("冻结的标题", "当前归属校验状态：UID_MISMATCH");
        binding.setBindingStatus("BOUND");
        binding.setPublishedSnapshotJson(binding.getPublishedSnapshotJson().replace("version-1", "another-version"));
        assertThat(service.buildPrompt(task)).contains("快照缺失或不匹配").doesNotContain("冻结的标题");
    }

    @Test
    void conflictingLiveCacheMustNotPolluteFrozenCopyOrMetrics() {
        binding.setPublishedVersionId("version-1");
        binding.setPublishedSnapshotJson("""
                {"versionId":"version-1","bvid":"BV16F411S72L","uid":"27058248",
                 "title":"冻结的标题","raw":{"desc":"冻结的简介"}}
                """);
        video.setRawSnapshot("{\"owner\":{\"mid\":123},\"stat\":{\"reply\":999}}");
        assertThat(service.buildPrompt(task)).contains("冻结的标题", "冻结的简介", "播放量：未提供", "平台评论总量：未提供")
                .doesNotContain("播放量：490", "平台评论总量：999");
    }

    private final CreatorBilibiliMapper mapper = mock(CreatorBilibiliMapper.class);
    private final CreatorPublishedVideoContextService service = new CreatorPublishedVideoContextService(mapper, new ObjectMapper());
    private final CreatorTaskRecord task = new CreatorTaskRecord();
    private final TaskVideoBindingRecord binding = new TaskVideoBindingRecord();
    private final BilibiliVideoRecord video = new BilibiliVideoRecord();

    CreatorPublishedVideoContextServiceTest() {
        task.setTaskId("task-1"); task.setUserId("default"); task.setTaskName("管理名称不能作为正式标题");
        binding.setTaskId("task-1"); binding.setUserId("default"); binding.setBindingStatus("BOUND");
        binding.setBvid("BV16F411S72L"); binding.setBilibiliUid("27058248");
        video.setBvid(binding.getBvid()); video.setBilibiliUid(binding.getBilibiliUid()); video.setSyncStatus("SYNCED");
        video.setTitle("正式标题"); video.setPublishTime(LocalDateTime.of(2024,2,3,10,20));
        video.setLastSyncTime(LocalDateTime.of(2026,10,14,11,30));
        video.setViewCount(490L); video.setCoinCount(0L); video.setLikeCount(7L); video.setFavoriteCount(8L);
        video.setRawSnapshot("""
                {"bvid":"BV16F411S72L","owner":{"mid":27058248},"desc":"正式简介原文","tname":"电子竞技","tid":171,
                 "tags":["CS2",{"tag_name":"游戏"}],"stat":{"reply":6,"danmaku":0},"secretOrUnrelated":"不应传入模型"}
                """);
        when(mapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(binding));
        when(mapper.findVideoByBvidAndUid(binding.getBvid(), binding.getBilibiliUid())).thenReturn(Optional.of(video));
    }

    @Test
    void shouldReadVerifiedPublishedMetadataAndWhitelistedCounts() {
        assertThat(service.buildPrompt(task)).contains("正式标题：正式标题", "正式简介：正式简介原文", "分区名称：电子竞技",
                "分区 ID：171", "标签：CS2、游戏", "2024-02-03T10:20", "2026-10-14T11:30",
                "投币数：0", "平台评论总量：6", "平台弹幕总量：0", "分享数：未提供", "未读取已发布视频本体")
                .doesNotContain(task.getTaskName(), "secretOrUnrelated", "不应传入模型");
        verify(mapper, never()).insertVideo(any());
    }

    @Test
    void shouldNotReadCacheForUnverifiedOrForeignBinding() {
        binding.setBindingStatus("WAITING_VERIFY");
        assertThat(service.buildPrompt(task)).contains("没有归属核验通过").doesNotContain("正式标题：");
        binding.setBindingStatus("BOUND"); binding.setUserId("other-user");
        assertThat(service.buildPrompt(task)).contains("没有归属核验通过");
        binding.setUserId("default"); binding.setTaskId("other-task");
        assertThat(service.buildPrompt(task)).contains("没有归属核验通过");
        verify(mapper, never()).findVideoByBvidAndUid(anyString(), anyString());
    }

    @Test
    void shouldHandleAbsentBindingAndCacheWithoutBlockingManualFeedback() {
        when(mapper.findBindingByTaskId("task-1")).thenReturn(Optional.empty());
        assertThat(service.buildPrompt(task)).contains("未提供", "没有归属核验通过");
        when(mapper.findBindingByTaskId("task-1")).thenReturn(Optional.of(binding));
        when(mapper.findVideoByBvidAndUid(binding.getBvid(),binding.getBilibiliUid())).thenReturn(Optional.empty());
        assertThat(service.buildPrompt(task)).contains("缓存缺失");
    }

    @Test
    void shouldRejectMismatchingOrUnsyncedCache() {
        video.setBvid("BV1kaQhBpEmF");
        assertThat(service.buildPrompt(task)).contains("缓存缺失").doesNotContain(video.getTitle());
        video.setBvid(binding.getBvid()); video.setBilibiliUid("123");
        assertThat(service.buildPrompt(task)).contains("缓存缺失");
        video.setBilibiliUid(binding.getBilibiliUid()); video.setSyncStatus("FAILED");
        assertThat(service.buildPrompt(task)).contains("缓存缺失");
    }

    @Test
    void shouldRejectConflictingRawSnapshotOwnership() {
        for (String raw : new String[]{"{\"owner\":{\"mid\":123}}", "{\"mid\":123}", "{\"bvid\":\"BV1kaQhBpEmF\"}"}) {
            video.setRawSnapshot(raw);
            assertThat(service.buildPrompt(task)).contains("原始快照与已核验 BV 或 UID 不一致").doesNotContain(video.getTitle());
        }
    }

    @Test
    void shouldPreserveVerifiedColumnsWithMalformedLegacySnapshot() {
        for (String raw : new String[]{null, "{\"desc\":", "[]", "null"}) {
            video.setRawSnapshot(raw);
            assertThat(service.buildPrompt(task)).contains("正式标题：正式标题", "正式简介：未提供", "分区名称：未提供",
                    "标签：未提供", "平台评论总量：未提供", "原始补充资料缺失或不可解析", "投币数：0");
        }
    }

    @Test
    void shouldDistinguishEmptyMetadataFromMissingAndInvalidCounters() {
        video.setRawSnapshot("{\"desc\":\"\",\"tags\":[],\"stat\":{\"reply\":0,\"danmaku\":-1},\"tid\":171.5}");
        assertThat(service.buildPrompt(task)).contains("正式简介：接口返回空文本", "标签：接口返回空列表",
                "分区名称：未提供", "分区 ID：未提供", "平台评论总量：0", "平台弹幕总量：未提供");
    }

    @Test
    void shouldReadArchiveDescriptionWithoutInventingTagsOrDetailCounts() {
        video.setRawSnapshot("{\"mid\":27058248,\"description\":\"列表接口简介\",\"typeid\":171}");
        assertThat(service.buildPrompt(task)).contains("正式简介：列表接口简介", "分区 ID：171", "分区名称：未提供",
                "标签：未提供", "平台评论总量：未提供");
    }

    @Test
    void shouldPreserveBothCategoryIdsWhenPublicInterfaceReturnsEmptyNames() {
        video.setRawSnapshot("{\"tid\":65,\"tname\":\"\",\"tid_v2\":2071,\"tname_v2\":\"\"}");
        assertThat(service.buildPrompt(task)).contains("分区 ID：65", "新版分区 ID：2071",
                "分区名称：接口返回空文本", "新版分区名称：接口返回空文本", "标签：未提供");
    }

    @Test
    void shouldReflectRefreshAndBoundLongTextWithoutLosingSourceTimes() {
        String original = service.buildPrompt(task);
        video.setTitle("刷新后的正式标题");
        video.setRawSnapshot("{\"desc\":\"" + "长".repeat(4100) + "\"}");
        video.setLastSyncTime(video.getLastSyncTime().plusMinutes(1));
        String refreshed = service.buildPrompt(task);
        assertThat(refreshed).isNotEqualTo(original).contains("刷新后的正式标题", "原文过长，后续已截断", "2026-10-14T11:31")
                .doesNotContain("长".repeat(4100));
    }
}
