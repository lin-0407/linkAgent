package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.link.linkagent.creator.creation.service.CreationInputRules.conflict;

/** 把已探测可用的系统成片交给真实媒体链路；与等待依赖同事务，运行和闭环不会只落一半。 */
@Service
@Transactional
public class CreationMediaService {
    private final CreationMediaMapper links;
    private final GeneratedDraftMapper drafts;
    private final CreationRuntimeService runtime;

    public CreationMediaService(CreationMediaMapper links, GeneratedDraftMapper drafts, CreationRuntimeService runtime) {
        this.links = links; this.drafts = drafts; this.runtime = runtime;
    }

    public void begin(GeneratedDraftLease lease, String versionId) {
        runtime.requireProductionLease(lease);
        var attempt = drafts.registered(lease.taskId(), lease.ownerId(), lease.runId())
                .orElseThrow(() -> conflict("本次运行尚未登记系统成片"));
        if (!attempt.versionId().equals(versionId)) throw conflict("媒体处理版本不属于本次运行");
        var existing = links.find(lease.runId());
        if (existing.isPresent()) {
            // 同一运行只处理一个精确版本；重复扫描沿用已有闭环，不新建子任务。
            if (!existing.get().versionId().equals(versionId)) throw conflict("本次运行已进入其他版本的媒体处理");
        } else {
            links.insert(new CreationMediaLink(lease.runId(), lease.taskId(), lease.ownerId(), versionId,
                    null, null, "MEDIA_PENDING", lease.generation(), null));
        }
        runtime.waitForDependency(lease.taskId(), lease.ownerId(), lease.runId(), lease.worker(),
                lease.generation(), versionId);
    }

    @Transactional(readOnly = true)
    public Optional<CreationMediaLink> find(String runId) {
        return links.find(runId);
    }
}
