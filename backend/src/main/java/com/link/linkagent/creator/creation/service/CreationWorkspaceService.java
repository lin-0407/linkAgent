package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationWorkspaceMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class CreationWorkspaceService {
    private final MediaUploadMapper media;
    private final CreationWorkspaceMapper workspace;
    private final CreationWorkspacePersistenceService persistence;
    private final ObjectStorageService storage;
    private final ObjectMapper json;

    public CreationWorkspaceService(MediaUploadMapper media, CreationWorkspaceMapper workspace,
            CreationWorkspacePersistenceService persistence, ObjectStorageService storage, ObjectMapper json) {
        this.media = media;
        this.workspace = workspace;
        this.persistence = persistence;
        this.storage = storage;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public CreationWorkspaceSnapshot snapshot(String taskId, String ownerId) {
        requireTask(taskId, ownerId);
        var record = media.findMediaWorkspace(taskId, ownerId).orElse(null);
        String selected = record == null ? media.findDraftVideo(taskId, ownerId).map(d -> d.versionId()).orElse(null)
                : record.currentVideoVersionId();
        JsonNode context = selected == null ? null : media.findVersionContext(taskId, ownerId, selected)
                .map(value -> parse(value.snapshotJson())).orElse(null);
        return new CreationWorkspaceSnapshot(record != null, record == null ? -1 : record.workspaceRevision(),
                selected, media.findPublishedVideoVersionId(taskId, ownerId).orElse(null), context);
    }

    @Transactional(readOnly = true)
    public CreationVersionList list(String taskId, String ownerId, int page, int size) {
        requireTask(taskId, ownerId);
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数不正确");
        }
        var snapshot = snapshot(taskId, ownerId);
        return new CreationVersionList(workspace.countVersions(taskId, ownerId), page, size,
                snapshot.workspaceRevision(), media.countPublishingFacts(taskId, ownerId) == 0,
                workspace.listVersions(taskId, ownerId,
                snapshot.currentVideoVersionId(), ((long) page - 1) * size, size));
    }

    public CreationWorkspaceSnapshot select(String taskId, String ownerId, String versionId, long revision) {
        requireTask(taskId, ownerId);
        var draft = persistence.requireSelectable(taskId, ownerId, versionId);
        try {
            var actual = storage.headObject(draft.bucketName(), draft.objectKey());
            if (actual.contentLength() != draft.fileSize()
                    || !"video/mp4".equalsIgnoreCase(actual.contentType())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "存储中的视频与版本记录不一致，不能切换");
            }
        } catch (MediaStorageException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法确认该版本媒体可用，请稍后重试");
        }
        persistence.select(taskId, ownerId, draft, revision);
        return snapshot(taskId, ownerId);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片上下文不可读取，请检查历史版本资料");
        }
    }

    private void requireTask(String taskId, String ownerId) {
        if (media.countTaskByOwner(taskId, ownerId) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在");
        }
    }
}
