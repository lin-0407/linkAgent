package com.link.linkagent.creator.creation.asset.service;

import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.Part;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Objects;

@Service
public class CreationAssetPersistence {
    public static final long PART_BYTES = 8L * 1024 * 1024;
    private final CreationAssetMapper mapper;
    public CreationAssetPersistence(CreationAssetMapper mapper) { this.mapper = mapper; }

    @Transactional
    public CreationAsset create(CreationAsset input) {
        if (mapper.lockTask(input.getTaskId(),input.getOwnerId()).isEmpty()) throw missing();
        var old = mapper.findKey(input.getTaskId(),input.getOwnerId(),input.getIdempotencyKey());
        if (old.isPresent()) {
            var a = old.get();
            if (!Objects.equals(a.getKind(), input.getKind()) || !Objects.equals(a.getFileName(),input.getFileName())
                    || a.getExpectedBytes()!=input.getExpectedBytes() || !Objects.equals(a.getExpectedSha256(),input.getExpectedSha256())
                    || !Objects.equals(a.getContentType(),input.getContentType())) throw conflict("同一幂等键对应了不同素材，请使用新键上传");
            return a;
        }
        mapper.insert(input);
        return mapper.find(input.getTaskId(),input.getOwnerId(),input.getAssetId()).orElseThrow();
    }

    @Transactional
    public void register(String taskId, String ownerId, String assetId, List<Part> parts) {
        var a = ownedLock(taskId,ownerId,assetId);
        if (!"UPLOADING".equals(a.getStatus())) throw conflict("素材当前不能登记分片");
        var seen = new java.util.HashSet<Integer>();
        for (var p : parts) {
            int count=totalParts(a.getExpectedBytes());
            if (!seen.add(p.partNumber()) || p.partNumber()<1 || p.partNumber()>count
                    || p.partSize()!=Math.min(PART_BYTES,a.getExpectedBytes()-(p.partNumber()-1)*PART_BYTES)
                    || p.etag()==null || p.etag().isBlank() || p.etag().length()>255
                    || p.etag().chars().anyMatch(c->c<32)) throw conflict("分片序号、字节数或 ETag 不匹配");
            mapper.savePart(assetId,p);
        }
    }

    @Transactional
    public CreationAsset complete(String taskId,String ownerId,String assetId) {
        var a=ownedLock(taskId,ownerId,assetId);
        if (List.of("COMPLETING","UPLOADED","PREPARING","READY").contains(a.getStatus())) return a;
        if (!"UPLOADING".equals(a.getStatus())) throw conflict("素材当前不能完成上传");
        if (mapper.parts(assetId).size()!=totalParts(a.getExpectedBytes())) throw conflict("素材分片尚未全部登记");
        mapper.transition(assetId,"UPLOADING","COMPLETING");
        a.setStatus("COMPLETING"); return a;
    }

    @Transactional
    public void cancel(String taskId,String ownerId,String assetId) {
        var a=ownedLock(taskId,ownerId,assetId);
        if ("READY".equals(a.getStatus())) throw conflict("已准备的素材需要保留历史引用，不能通过取消上传删除");
        mapper.cancel(assetId);
    }

    @Transactional
    public void retry(String taskId,String ownerId,String assetId) {
        var a=ownedLock(taskId,ownerId,assetId);
        if (!"FAILED".equals(a.getStatus())) throw conflict("只有失败素材可以重试");
        String next="PREPARING".equals(a.getFailedStage()) ? "UPLOADED" : a.getFailedStage();
        if (!List.of("CREATING","COMPLETING","UPLOADED").contains(next)) throw conflict("素材失败阶段不能恢复");
        mapper.transition(assetId,"FAILED",next);
    }

    private CreationAsset ownedLock(String taskId,String ownerId,String assetId) {
        // 与任务删除按相同顺序加锁，防止晚到的请求继续操作已删除任务。
        if (mapper.lockTask(taskId,ownerId).isEmpty()) throw missing();
        var a=mapper.lock(assetId);
        if (a==null || !a.getTaskId().equals(taskId) || !a.getOwnerId().equals(ownerId)) throw missing();
        return a;
    }
    public static int totalParts(long size) { return (int)((size+PART_BYTES-1)/PART_BYTES); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"创作任务或素材不存在"); }
}
