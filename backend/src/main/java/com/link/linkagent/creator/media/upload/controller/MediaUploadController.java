package com.link.linkagent.creator.media.upload.controller;

import com.link.linkagent.creator.media.upload.model.CreateMediaUploadRequest;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartSignRequest;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartSignResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartsCompleteRequest;
import com.link.linkagent.creator.media.upload.model.MediaUploadResponse;
import com.link.linkagent.creator.media.upload.service.MediaUploadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成片分片上传接口。
 * <p>
 * 单人自托管工作台统一使用 default 作为媒体归属，不接收客户端传入的 userId，
 * 避免将媒体上传流程扩展为多用户鉴权体系。
 * <p>
 * 所有路径参数使用正则校验，防止 SQL 注入和路径遍历（虽然 MyBatis 参数化查询已防注入，
 * 但额外一层正则校验可以作为纵深防御）。
 */
@Validated // 开启方法级别参数校验（@PathVariable 上的 @Pattern 等）
@RestController
// 只有媒体能力启用时才注册此 Controller，否则 404
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/draft-video/uploads")
public class MediaUploadController {

    // taskId 和 uploadSessionId 的安全格式：仅允许字母、数字、下划线、连字符，1-64 字符
    // 拒绝特殊字符，作为纵深防御（即使 MyBatis 参数化查询已防止 SQL 注入）
    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    // 项目是单人自托管工作台，媒体归属与现有创作任务默认用户保持一致。
    private static final String DEFAULT_OWNER_ID = "default";

    private final MediaUploadService mediaUploadService;

    public MediaUploadController(MediaUploadService mediaUploadService) {
        this.mediaUploadService = mediaUploadService;
    }

    /**
     * 创建成片分片上传会话。
     * <p>
     * POST /api/creator/tasks/{taskId}/draft-video/uploads
     * <p>
     * 请求头：
     * - Idempotency-Key：浏览器生成的幂等键（如 taskId + fileFingerprint 的哈希），
     *   同一任务同一文件重复请求返回已有会话，防止重复创建 OSS Multipart Upload
     * <p>
     * 请求体：CreateMediaUploadRequest（版本名、文件名、大小、类型、最后修改时间）
     * <p>
     * 响应：MediaUploadResponse（含 uploadSessionId、分片参数等）
     */
    @PostMapping
    public MediaUploadResponse createUpload(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,

            @RequestHeader("Idempotency-Key") // 幂等键来自请求头，而非请求体
            @NotBlank(message = "Idempotency-Key不能为空")
            @Size(max = 128, message = "Idempotency-Key长度不能超过128个字符")
            String idempotencyKey,

            @Valid @RequestBody CreateMediaUploadRequest request) {
        return mediaUploadService.createUpload(DEFAULT_OWNER_ID, taskId, idempotencyKey, request);
    }

    /**
     * 获取上传会话快照，供前端页面刷新后恢复续传状态。
     * <p>
     * GET /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}
     */
    @GetMapping("/{uploadSessionId}")
    public MediaUploadResponse getUpload(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId) {
        return mediaUploadService.getUpload(DEFAULT_OWNER_ID, taskId, uploadSessionId);
    }

    /**
     * 查询任务当前活跃上传会话，浏览器本地续传指针丢失时仍可恢复服务端状态。
     */
    @GetMapping("/current")
    public MediaUploadResponse getCurrentUpload(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId) {
        return mediaUploadService.getCurrentUpload(DEFAULT_OWNER_ID, taskId);
    }

    /**
     * 列出已登记的分片列表，供前端续传时判断哪些分片已完成。
     * <p>
     * GET /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}/parts
     */
    @GetMapping("/{uploadSessionId}/parts")
    public List<MediaUploadPartResponse> listParts(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId) {
        return mediaUploadService.listParts(DEFAULT_OWNER_ID, taskId, uploadSessionId);
    }

    /**
     * 为指定分片批量生成短时预签名 PUT URL。
     * <p>
     * POST /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}/parts:sign
     * <p>
     * 响应中禁用缓存（CacheControl.noStore()），因为预签名 URL 是短时 Bearer 凭证，
     * 被中间代理或浏览器缓存后可能导致过期 URL 被重复使用。
     */
    @PostMapping("/{uploadSessionId}/parts:sign")
    public ResponseEntity<MediaUploadPartSignResponse> signParts(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId,
            @Valid @RequestBody MediaUploadPartSignRequest request) {
        MediaUploadPartSignResponse response = mediaUploadService.signParts(
                DEFAULT_OWNER_ID, taskId, uploadSessionId, request.partNumbers());
        // 预签名 URL 是短时 Bearer 凭证，禁止浏览器或中间代理缓存响应
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // 禁用所有缓存
                .body(response);
    }

    /**
     * 批量登记浏览器已完成上传的分片 ETag。
     * <p>
     * POST /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}/parts:complete
     * <p>
     * 浏览器 PUT 成功后从响应头拿到 ETag，调用本接口登记。
     * 返回最新分片列表供前端确认登记结果。
     */
    @PostMapping("/{uploadSessionId}/parts:complete")
    public List<MediaUploadPartResponse> registerCompletedParts(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId,
            @Valid @RequestBody MediaUploadPartsCompleteRequest request) {
        return mediaUploadService.registerCompletedParts(
                DEFAULT_OWNER_ID,
                taskId,
                uploadSessionId,
                request
        );
    }

    /**
     * 完成上传：将所有已登记分片提交给 OSS 合并为完整对象。
     * <p>
     * POST /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}:complete
     * <p>
     * 这是上传流程的最后一步：OSS 合并分片 → HeadObject 校验 → 数据库状态更新。
     * 成功后返回 DraftVideoResponse（成片完整信息）。
     */
    @PostMapping("/{uploadSessionId}:complete")
    public DraftVideoResponse completeUpload(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId) {
        return mediaUploadService.completeUpload(DEFAULT_OWNER_ID, taskId, uploadSessionId);
    }

    /**
     * 取消上传：标记 ABORTED 并调用 OSS 释放未合并分片。
     * <p>
     * DELETE /api/creator/tasks/{taskId}/draft-video/uploads/{uploadSessionId}
     * <p>
     * 返回 204 No Content，无响应体。
     */
    @DeleteMapping("/{uploadSessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT) // DELETE 成功返回 204
    public void abortUpload(
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确") String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID_PATTERN, message = "上传会话ID格式不正确") String uploadSessionId) {
        mediaUploadService.abortUpload(DEFAULT_OWNER_ID, taskId, uploadSessionId);
    }
}
