package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.CreationWorkspaceMapper;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
public class CreationWorkspacePersistenceService {
    private final MediaUploadMapper media;
    private final CreationWorkspaceMapper workspace;
    private final CreatorMediaWorkflowGateService gate;

    public CreationWorkspacePersistenceService(MediaUploadMapper media, CreationWorkspaceMapper workspace,
                                                CreatorMediaWorkflowGateService gate) {
        this.media = media;
        this.workspace = workspace;
        this.gate = gate;
    }

    /** 网络检查已结束；只在短事务里复核上下文、版本身份和并发修订号。 */
    @Transactional
    public void select(String taskId, String ownerId, DraftVideoRecord verified, long expectedRevision) {
        media.lockMediaTask(taskId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
        media.lockTaskDraftVersions(taskId, ownerId);
        var current = media.findMediaWorkspace(taskId, ownerId)
                .orElseThrow(() -> conflict("该任务尚未建立可选版工作区"));
        if (current.workspaceRevision() != expectedRevision) {
            throw conflict("工作区已变化，当前修订号为 " + current.workspaceRevision()
                    + "，当前版本为 " + current.currentVideoVersionId() + "，请刷新后再选择");
        }
        DraftVideoRecord draft = requireSelectable(taskId, ownerId, verified.versionId());
        if (!Objects.equals(draft.bucketName(), verified.bucketName())
                || !Objects.equals(draft.objectKey(), verified.objectKey())
                || !Objects.equals(draft.fileSize(), verified.fileSize())) {
            throw conflict("成片对象已变化，请刷新后重新选择");
        }
        if (Objects.equals(current.currentVideoVersionId(), draft.versionId())) return;
        if (media.activateUploadedVersion(taskId, ownerId, draft.versionId(), expectedRevision) != 1) {
            throw conflict("工作区已变化，请刷新后重新选择");
        }
    }

    public DraftVideoRecord requireSelectable(String taskId, String ownerId, String versionId) {
        if (workspace.countActiveWork(taskId, ownerId) > 0) {
            throw conflict("任务仍有上传、预处理或试映在运行，请完成或取消后再选择版本");
        }
        var draft = media.findDraftVideoByVersion(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片版本不存在"));
        if (draft.mediaDeletedAt() != null) throw conflict("该版本媒体已删除，不能选为当前成片");
        if (media.findVersionContext(taskId, ownerId, versionId).isEmpty()) {
            throw conflict("该历史版本未保存可靠制作上下文，可查看历史，不能直接选回");
        }
        gate.ensureVersionReady(taskId, ownerId, versionId, "版本选择");
        return draft;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
