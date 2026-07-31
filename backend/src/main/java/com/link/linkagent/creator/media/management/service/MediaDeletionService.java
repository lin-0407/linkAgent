package com.link.linkagent.creator.media.management.service;

import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户主动删除成片媒体；文本报告和工作流事实不在删除范围内。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class MediaDeletionService {

    private final MediaUploadMapper uploadMapper;
    private final MediaProcessingMapper processingMapper;
    private final PreflightReviewMapper preflightMapper;
    private final ObjectStorageService storage;

    public MediaDeletionService(MediaUploadMapper uploadMapper,
                                MediaProcessingMapper processingMapper,
                                PreflightReviewMapper preflightMapper,
                                ObjectStorageService storage) {
        this.uploadMapper = uploadMapper;
        this.processingMapper = processingMapper;
        this.preflightMapper = preflightMapper;
        this.storage = storage;
    }

    /**
     * 删除当前版本的所有已知媒体对象。
     * 数据库行锁贯穿删除过程，是为了防止删除与新建媒体处理或试映任务同时发生。
     */
    @Transactional
    public void deleteMedia(String ownerId, String taskId, String versionId) {
        String normalizedTaskId = taskId.trim();
        String normalizedVersionId = versionId.trim();
        processingMapper.lockDraftVersion(normalizedTaskId, ownerId, normalizedVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        DraftVideoRecord draft = uploadMapper.findDraftVideoByVersion(
                        normalizedTaskId, ownerId, normalizedVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        if (draft.mediaDeletedAt() != null) {
            return;
        }
        ensureNoActiveMediaWork(draft);

        List<MediaUploadRecord> uploads = uploadMapper.listUploadsByVersion(
                normalizedTaskId, ownerId, normalizedVersionId);
        List<MediaProcessingAssetRecord> assets = processingMapper.listAssetsByVersion(normalizedVersionId);
        try {
            abortUnfinishedUploads(draft.bucketName(), uploads);
            deleteStoredObjects(draft, uploads, assets);
        } catch (MediaStorageException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "对象存储暂时不可用，媒体尚未全部删除，请稍后重试"
            );
        }

        processingMapper.markAssetsDeleted(normalizedVersionId);
        preflightMapper.markVersionEvidenceUnavailable(normalizedVersionId);
        uploads.forEach(upload -> uploadMapper.deleteParts(upload.uploadSessionId()));
        uploadMapper.markUploadsDeleted(normalizedTaskId, ownerId, normalizedVersionId);
        if (uploadMapper.markDraftMediaDeleted(normalizedTaskId, ownerId, normalizedVersionId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片删除状态已变化，请刷新后重试");
        }
    }

    private void ensureNoActiveMediaWork(DraftVideoRecord draft) {
        if (DraftVideoStatus.PROBING.name().equals(draft.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片信息正在检测，请完成后再删除媒体");
        }
        MediaUploadRecord upload = uploadMapper.findCurrentUpload(draft.taskId(), draft.ownerId()).orElse(null);
        if (upload != null && draft.versionId().equals(upload.versionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片仍在上传或确认，请先取消上传");
        }
        MediaProcessingJobRecord processing = processingMapper
                .findCurrentJob(draft.taskId(), draft.ownerId(), draft.versionId())
                .orElse(null);
        if (processing != null && ("QUEUED".equals(processing.status()) || "RUNNING".equals(processing.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体处理仍在运行，请等待完成后再删除");
        }
        if (preflightMapper.findActiveByVersion(draft.taskId(), draft.ownerId(), draft.versionId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发布前试映仍在运行，请结束后再删除媒体");
        }
    }

    private void abortUnfinishedUploads(String bucketName, List<MediaUploadRecord> uploads) {
        for (MediaUploadRecord upload : uploads) {
            if (!"COMPLETED".equals(upload.status())
                    && upload.storageUploadId() != null
                    && !upload.storageUploadId().isBlank()) {
                storage.abortMultipartUpload(bucketName, upload.objectKey(), upload.storageUploadId());
            }
        }
    }

    private void deleteStoredObjects(DraftVideoRecord draft,
                                     List<MediaUploadRecord> uploads,
                                     List<MediaProcessingAssetRecord> assets) {
        Map<String, StoredMediaObject> objects = new LinkedHashMap<>();
        for (MediaProcessingAssetRecord asset : assets) {
            addObject(objects, asset.bucketName(), asset.objectKey());
        }
        for (MediaUploadRecord upload : uploads) {
            addObject(objects, draft.bucketName(), upload.objectKey());
        }
        addObject(objects, draft.bucketName(), draft.objectKey());
        objects.values().forEach(object -> storage.deleteObject(object.bucketName(), object.objectKey()));
    }

    private void addObject(Map<String, StoredMediaObject> objects, String bucketName, String objectKey) {
        if (bucketName == null || bucketName.isBlank() || objectKey == null || objectKey.isBlank()) {
            return;
        }
        objects.putIfAbsent(bucketName + "\n" + objectKey, new StoredMediaObject(bucketName, objectKey));
    }

    private record StoredMediaObject(String bucketName, String objectKey) {
    }
}
