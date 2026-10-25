package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.*;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartsCompleteRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
import static com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence.*;

@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class CreationAssetService {
    private final CreationAssetMapper mapper;
    private final CreationAssetPersistence persistence;
    private final ObjectStorageProperties properties;
    private final ObjectStorageService storage;
    private final ObjectMapper json;
    public CreationAssetService(CreationAssetMapper mapper,CreationAssetPersistence persistence,
            ObjectStorageProperties properties,ObjectStorageService storage,ObjectMapper json) {
        this.mapper=mapper; this.persistence=persistence; this.properties=properties; this.storage=storage; this.json=json;
    }

    public Snapshot create(String taskId,String ownerId,String key,Create request) {
        properties.validateConfigured();
        String name=request.fileName().strip();
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        Set<String> allowed=switch(request.kind()) {
            case "IMAGE" -> Set.of("png","jpg","jpeg");
            case "NARRATION","MUSIC" -> Set.of("wav","mp3","m4a");
            case "VIDEO_CLIP" -> Set.of("mp4","mov");
            default -> Set.of();
        };
        if (name.lastIndexOf('.')<=0 || !allowed.contains(ext) || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c->c<32))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"素材类型或文件名不支持");
        long max="IMAGE".equals(request.kind()) ? 30L*1024*1024 : 1024L*1024*1024;
        if(request.fileSize()<1 || request.fileSize()>max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"素材超过当前类型的大小上限");
        var a=new CreationAsset();
        a.setAssetId(UUID.randomUUID().toString()); a.setTaskId(taskId); a.setOwnerId(ownerId);
        a.setIdempotencyKey(key); a.setKind(request.kind()); a.setFileName(name);
        // 实际容器由准备模块验证；存储类型由受控扩展名决定，不接受任意响应类型。
        a.setContentType(switch(ext) {
            case "png" -> "image/png"; case "jpg","jpeg" -> "image/jpeg";
            case "wav" -> "audio/wav"; case "mp3" -> "audio/mpeg"; case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac"; case "mov" -> "video/quicktime"; default -> "video/mp4";
        });
        a.setExpectedBytes(request.fileSize()); a.setExpectedSha256(request.sha256().toLowerCase(Locale.ROOT));
        a.setBucketName(properties.getBucket());
        a.setSourceKey("users/"+ownerId+"/tasks/"+taskId+"/assets/"+a.getAssetId()+"/original/source."+ext);
        return snapshot(persistence.create(a));
    }

    public Snapshot get(String taskId,String ownerId,String assetId) { return snapshot(require(taskId,ownerId,assetId)); }
    public List<Snapshot> list(String taskId,String ownerId,int offset,int limit) {
        if(mapper.lockTask(taskId,ownerId).isEmpty()) throw missing();
        return mapper.list(taskId,ownerId,offset,limit).stream().map(this::snapshot).toList();
    }

    public List<PresignedUploadPart> sign(String taskId,String ownerId,String assetId,List<Integer> parts) {
        var a=require(taskId,ownerId,assetId);
        if(!"UPLOADING".equals(a.getStatus())) throw conflict("上传会话尚未就绪或已经结束");
        ensureUploadBucket(a);
        var seen=new HashSet<Integer>();
        for(Integer p:parts) if(p==null || p<1 || p>totalParts(a.getExpectedBytes()) || !seen.add(p)) throw conflict("分片序号不合法或重复");
        return parts.stream().map(p->storage.presignUploadPart(a.getSourceKey(),a.getStorageUploadId(),p,Duration.ofMinutes(10))).toList();
    }
    public Snapshot register(String taskId,String ownerId,String assetId,MediaUploadPartsCompleteRequest request) {
        if(request.parts().stream().anyMatch(Objects::isNull)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分片记录不能为空");
        persistence.register(taskId,ownerId,assetId,request.parts().stream().map(p->new Part(p.partNumber(),p.etag(),p.partSize())).toList());
        return get(taskId,ownerId,assetId);
    }
    public Snapshot complete(String taskId,String ownerId,String assetId) {
        var a=require(taskId,ownerId,assetId);
        if("UPLOADING".equals(a.getStatus()) || "COMPLETING".equals(a.getStatus())) ensureUploadBucket(a);
        persistence.complete(taskId,ownerId,assetId); return get(taskId,ownerId,assetId);
    }
    public Snapshot cancel(String taskId,String ownerId,String assetId) { persistence.cancel(taskId,ownerId,assetId); return get(taskId,ownerId,assetId); }
    public record DeletionImpact(String assetId,int references,boolean allowed,String message) { }
    public DeletionImpact deletionImpact(String task,String owner,String id) {
        var asset=require(task,owner,id);int references=mapper.referenceCount(task,owner,id);
        boolean allowed="READY".equals(asset.getStatus()) && references==0 && mapper.activeExecutions(task,owner)==0;
        return new DeletionImpact(id,references,allowed,allowed?"该素材没有简报或历史工程引用，可删除私有媒体。":"素材仍有历史引用、活动制作或尚未准备完成；删除将被拒绝。");
    }
    public Snapshot deleteReady(String task,String owner,String id){persistence.deleteReady(task,owner,id);return get(task,owner,id);}
    public Snapshot retry(String taskId,String ownerId,String assetId) { persistence.retry(taskId,ownerId,assetId); return get(taskId,ownerId,assetId); }

    public PresignedObjectRead read(String taskId,String ownerId,String assetId,String role) {
        var a=require(taskId,ownerId,assetId);
        if(!"READY".equals(a.getStatus())) throw conflict("素材尚未准备完成，不能预览或下载");
        String objectKey; long bytes;
        if("ORIGINAL".equals(role)) {objectKey=a.getSourceKey();bytes=a.getExpectedBytes();}
        else {
            var file=objects(a).stream().filter(f->f.role().equals(role)).findFirst().orElseThrow(()->conflict("素材没有该类型的文件"));
            objectKey=file.objectKey(); bytes=file.bytes();
        }
        if(storage.headObject(a.getBucketName(),objectKey).contentLength()!=bytes) throw conflict("素材对象已缺失或发生变化");
        return storage.presignBrowserGetObject(a.getBucketName(),objectKey,Duration.ofMinutes(5));
    }

    public CreationAsset require(String taskId,String ownerId,String assetId) {
        return mapper.find(taskId,ownerId,assetId).orElseThrow(CreationAssetPersistence::missing);
    }
    public void ensureUploadBucket(CreationAsset a) {
        if(!Objects.equals(a.getBucketName(),properties.getBucket())) throw conflict("上传期间存储桶配置发生变化，请恢复原配置后继续");
    }
    public List<StoredFile> objects(CreationAsset a) {
        try { return a.getObjectsJson()==null ? List.of() : json.readValue(a.getObjectsJson(),new TypeReference<List<StoredFile>>(){}); }
        catch(Exception ex) {throw new IllegalStateException("素材派生文件记录无法读取");}
    }
    private Snapshot snapshot(CreationAsset a) {
        try { return new Snapshot(a.getAssetId(),a.getKind(),a.getFileName(),a.getExpectedBytes(),a.getExpectedSha256(),
                a.getStatus(),a.getFailedStage(),a.getFailureCode(),a.getFailureMessage(),a.getMetadataJson()==null ? null : json.readTree(a.getMetadataJson()),
                PART_BYTES,totalParts(a.getExpectedBytes()),mapper.parts(a.getAssetId()),a.isCleanupPending(),a.getCreateTime(),a.getUpdateTime()); }
        catch(java.io.IOException ex) {throw new IllegalStateException("素材元数据无法读取");}
    }
}
