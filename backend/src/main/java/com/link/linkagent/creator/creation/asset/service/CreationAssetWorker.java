package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.media.storage.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 上传合并与媒体解码在事务外执行；只有当前活租约可以发布 READY。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class CreationAssetWorker implements AutoCloseable {
    private static final java.time.Duration STORAGE_TIMEOUT=java.time.Duration.ofSeconds(120);
    private static final Logger log=LoggerFactory.getLogger(CreationAssetWorker.class);
    private final CreationAssetMapper mapper;
    private final CreationAssetService service;
    private final ObjectStorageService storage;
    private final AssetMediaPreparer preparer;
    private final FixedProjectRendererProperties properties;
    private final ObjectMapper json;
    private final AtomicBoolean busy=new AtomicBoolean(),stopping=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("creation-asset-worker").factory());
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("creation-asset-heartbeat").factory());

    public CreationAssetWorker(CreationAssetMapper mapper,CreationAssetService service,ObjectStorageService storage,
            AssetMediaPreparer preparer,FixedProjectRendererProperties properties,ObjectMapper json) {
        this.mapper=mapper;this.service=service;this.storage=storage;this.preparer=preparer;this.properties=properties;this.json=json;
    }

    @Scheduled(fixedDelayString="${creator.creation.assets.poll-interval-ms:2000}")
    public void poll() {
        if(stopping.get() || !busy.compareAndSet(false,true)) return;
        try {
            executor.submit(()-> {try {sweepObjects();for(var a:mapper.candidates()) {if(stopping.get()) break; process(a);}}
                catch(RuntimeException ex) {log.warn("素材扫描暂不可用，类型={}",ex.getClass().getSimpleName());}
                finally {busy.set(false);}});
        } catch(RuntimeException ex) {busy.set(false);if(!stopping.get()) log.warn("素材扫描暂不可用，类型={}",ex.getClass().getSimpleName());}
    }

    /** 同步入口供受控测试推进同一数据库队列，不绕过领取与围栏。 */
    public void process(CreationAsset a) {
        if("CANCELLED".equals(a.getStatus())) {cleanup(a);return;}
        String phase="UPLOADED".equals(a.getStatus()) ? "PREPARING" : a.getStatus();
        if(!Set.of("CREATING","COMPLETING","PREPARING").contains(phase)) return;
        String token=UUID.randomUUID().toString();
        if(mapper.claim(a.getAssetId(),a.getStatus(),phase,token)!=1) return;
        var lost=new AtomicBoolean();
        var pulse=heartbeats.scheduleAtFixedRate(()-> {
            try {if(mapper.heartbeat(a.getAssetId(),token)!=1) lost.set(true);} catch(RuntimeException ex) {lost.set(true);}
        },0,10,TimeUnit.SECONDS);
        BooleanSupplier cancelled=()->stopping.get() || lost.get() || Thread.currentThread().isInterrupted();
        try {
            switch(phase) {
                case "CREATING" -> createUpload(a,token,cancelled);
                case "COMPLETING" -> completeUpload(a,token,cancelled);
                case "PREPARING" -> prepare(a,token,cancelled);
                default -> throw new IllegalStateException();
            }
        } catch(Exception ex) {
            if(!stopping.get() && !Thread.currentThread().isInterrupted()) {
                String code=ex instanceof AssetMediaPreparer.AssetPreparationException media ? media.code()
                        : ex instanceof AssetFailure own ? own.code : ex instanceof MediaStorageException ? "STORAGE_UNAVAILABLE" : "PREPARATION_FAILED";
                String message=ex instanceof AssetMediaPreparer.AssetPreparationException ? ex.getMessage() : failureMessage(code);
                try {mapper.failWithMessage(a.getAssetId(),token,code,message);} catch(RuntimeException ignored) {log.warn("素材结果等待租约恢复，素材={}",a.getAssetId());}
            }
        } finally {
            pulse.cancel(false);
            // 合并可能在取消清理之后才落到存储，晚到的执行仍须再收尾一次。
            cleanupIfCancelled(a);
        }
    }

    private void createUpload(CreationAsset a,String token,BooleanSupplier cancelled) {
        service.ensureUploadBucket(a); check(cancelled);
        var existing=storage.listMultipartUploads(a.getBucketName(),a.getSourceKey());
        requireLive(a,token,cancelled);
        var handle=existing.isEmpty() ? storage.createMultipartUpload(a.getSourceKey(),a.getContentType(),STORAGE_TIMEOUT) : existing.getFirst();
        for(int i=1;i<existing.size();i++) storage.abortMultipartUpload(a.getBucketName(),a.getSourceKey(),existing.get(i).uploadId());
        boolean saved=false;
        try {check(cancelled);saved=mapper.finish(a.getAssetId(),token,"UPLOADING",handle.uploadId(),null,null,null)==1;}
        finally {
            // 失租时新代可能正采用同一句柄，未登记不等于无人引用；只在明确取消时撤销。
            if(!saved) {
                var current=mapper.find(a.getTaskId(),a.getOwnerId(),a.getAssetId());
                if(current.isPresent() && "CANCELLED".equals(current.get().getStatus()))
                    storage.abortMultipartUpload(a.getBucketName(),a.getSourceKey(),handle.uploadId());
            }
        }
    }

    private void completeUpload(CreationAsset a,String token,BooleanSupplier cancelled) {
        service.ensureUploadBucket(a); check(cancelled);
        try {
            storage.completeMultipartUpload(a.getSourceKey(),a.getStorageUploadId(),mapper.parts(a.getAssetId()).stream()
                    .map(p->new CompletedUploadPart(p.partNumber(),p.etag())).toList(),STORAGE_TIMEOUT);
        } catch(MediaStorageException ambiguous) {
            // 响应丢失后先核实同一不可变对象；实际摘要仍由下一步全量读取复核。
            if(storage.headObject(a.getBucketName(),a.getSourceKey()).contentLength()!=a.getExpectedBytes()) throw ambiguous;
        }
        if(storage.headObject(a.getBucketName(),a.getSourceKey()).contentLength()!=a.getExpectedBytes()) throw new AssetFailure("SIZE_MISMATCH");
        check(cancelled);
        if(mapper.finish(a.getAssetId(),token,"UPLOADED",null,null,null,null)!=1) cleanupIfCancelled(a);
    }

    private void prepare(CreationAsset a,String token,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if(storage.headObject(a.getBucketName(),a.getSourceKey()).contentLength()!=a.getExpectedBytes()) throw new AssetFailure("SIZE_MISMATCH");
        Path root=Path.of(properties.getWorkRoot()).toAbsolutePath().normalize().resolve("assets");
        Files.createDirectories(root);
        if(!root.toRealPath().equals(root)) throw new AssetFailure("WORKSPACE_UNAVAILABLE");
        // 先预留最坏情况下的源、工作音轨和视频空间，避免占满宿主磁盘。
        if(Files.getFileStore(root).getUsableSpace()<a.getExpectedBytes()+4L*1024*1024*1024+128L*1024*1024) throw new AssetFailure("DISK_SPACE_INSUFFICIENT");
        Path work=Files.createTempDirectory(root,a.getAssetId()+"-");
        var uploaded=new ArrayList<StoredFile>();
        boolean committed=false;
        try {
            String ext=a.getFileName().substring(a.getFileName().lastIndexOf('.')).toLowerCase(Locale.ROOT);
            Path source=work.resolve("source"+ext);
            storage.downloadObject(a.getBucketName(),a.getSourceKey(),source,STORAGE_TIMEOUT);
            check(cancelled);
            if(Files.size(source)!=a.getExpectedBytes()) throw new AssetFailure("SIZE_MISMATCH");
            String digest=sha256(source,cancelled);
            if(!digest.equals(a.getExpectedSha256())) throw new AssetFailure("SHA256_MISMATCH");
            Path output=Files.createDirectory(work.resolve("prepared"));
            var result=preparer.prepare(source,output,a.getKind(),cancelled);
            for(var f:result.files()) {
                check(cancelled);
                String key=a.getSourceKey().substring(0,a.getSourceKey().lastIndexOf("/original/"))+"/prepared/"+token+"/"+f.fileName();
                var file=new StoredFile(f.role(),key,f.contentType(),Files.size(f.path()),sha256(f.path(),cancelled));
                if(mapper.registerObject(a.getAssetId(),token,f.role(),key)!=1) throw new AssetFailure("LEASE_LOST");
                uploaded.add(file);
                requireLive(a,token,cancelled);
                storage.putObject(a.getBucketName(),key,f.path(),f.contentType(),STORAGE_TIMEOUT);
                // 只有远端明确确认写完，才能提前解除清理屏障；超时或丢响应仍保留延后核对。
                mapper.objectUploaded(a.getAssetId(),token,f.role());
                if(storage.headObject(a.getBucketName(),key).contentLength()!=file.bytes()) throw new AssetFailure("DERIVED_SIZE_MISMATCH");
                // 不能用 PUT 成功或 HEAD 大小代替内容一致性验收。
                Path verify=work.resolve("verify-"+f.fileName());
                storage.downloadObject(a.getBucketName(),key,verify,STORAGE_TIMEOUT);
                if(Files.size(verify)!=file.bytes() || !sha256(verify,cancelled).equals(file.sha256())) throw new AssetFailure("DERIVED_SHA256_MISMATCH");
                Files.delete(verify);
            }
            check(cancelled);
            var meta=json.createObjectNode();meta.set("original",result.originalMetadata());meta.set("normalized",result.normalizedMetadata());
            committed=mapper.finish(a.getAssetId(),token,"READY",null,digest,json.writeValueAsString(meta),json.writeValueAsString(uploaded))==1;
        } finally {
            if(!committed && !uploaded.isEmpty()) {
                try {
                    var current=mapper.find(a.getTaskId(),a.getOwnerId(),a.getAssetId());
                    // 数据库确认不了是否已提交时保留文件，避免删除一次成功但丢响应的 READY 结果。
                    // 软删除任务也会让公开归属查询为空，不能据此认定历史素材未被引用。
                    if(current.isPresent()) {
                        var retained=service.objects(current.get());
                        for(var f:uploaded) if(retained.stream().noneMatch(x->x.objectKey().equals(f.objectKey()))) {
                            storage.deleteObject(a.getBucketName(),f.objectKey());
                            mapper.objectCleaned(a.getAssetId(),token,f.role());
                        }
                    }
                } catch(RuntimeException ex) {log.warn("素材派生文件清理待恢复，素材={}",a.getAssetId());}
            }
            deleteWork(work);
            cleanupIfCancelled(a);
        }
    }

    private void cleanupIfCancelled(CreationAsset a) {
        mapper.find(a.getTaskId(),a.getOwnerId(),a.getAssetId()).filter(x->"CANCELLED".equals(x.getStatus())).ifPresent(this::cleanup);
    }
    private void cleanup(CreationAsset a) {
        if(mapper.cleanupDue(a.getAssetId())!=1) return;
        try {
            if(a.getStorageUploadId()!=null) storage.abortMultipartUpload(a.getBucketName(),a.getSourceKey(),a.getStorageUploadId());
            for(var upload:storage.listMultipartUploads(a.getBucketName(),a.getSourceKey())) storage.abortMultipartUpload(a.getBucketName(),a.getSourceKey(),upload.uploadId());
            storage.deleteObject(a.getBucketName(),a.getSourceKey());
            for(var f:service.objects(a)) storage.deleteObject(a.getBucketName(),f.objectKey());
            mapper.cleaned(a.getAssetId());
            mapper.cleanupDeferred(a.getAssetId());
        } catch(RuntimeException ex) {log.warn("取消的素材等待存储清理，素材={}",a.getAssetId());}
    }
    public void sweepObjects() {
        for(var o:mapper.pendingObjects()) {
            try {storage.deleteObject(o.bucketName(),o.objectKey());mapper.objectCleaned(o.assetId(),o.leaseToken(),o.role());}
            catch(RuntimeException ex) {log.warn("素材派生对象等待下次清理，素材={}",o.assetId());}
        }
    }
    private static void deleteWork(Path work) {
        try(var paths=Files.walk(work)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);}
        catch(java.io.IOException ex) {log.warn("素材临时目录未能完全清理");}
    }
    public static String sha256(Path file,BooleanSupplier cancelled) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(file)) {byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1) {check(cancelled);digest.update(b,0,n);}}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void check(BooleanSupplier cancelled) {if(cancelled.getAsBoolean()) throw new AssetFailure("CANCELLED");}
    private void requireLive(CreationAsset a,String token,BooleanSupplier cancelled) {
        check(cancelled);
        if(mapper.heartbeat(a.getAssetId(),token)!=1) throw new AssetFailure("LEASE_LOST");
    }
    private static class AssetFailure extends RuntimeException {final String code;AssetFailure(String code) {super(code);this.code=code;}}
    private static String failureMessage(String code) {
        return switch(code) {
            case "SHA256_MISMATCH" -> "上传内容与声明的文件摘要不同，请选择原文件或重新上传";
            case "SIZE_MISMATCH" -> "上传文件的实际字节数不匹配，请重新检查分片";
            case "DISK_SPACE_INSUFFICIENT" -> "工作磁盘空间不足，释放空间后可重试";
            case "DERIVED_SIZE_MISMATCH","DERIVED_SHA256_MISMATCH" -> "规范化文件保存后内容校验不一致，请重试";
            case "STORAGE_UNAVAILABLE" -> "私有存储暂时不可用，恢复后可重试或取消";
            default -> "素材准备失败，请检查媒体文件与运行环境后重试";
        };
    }
    @Override @PreDestroy public void close() {stopping.set(true);executor.shutdownNow();heartbeats.shutdownNow();try {executor.awaitTermination(10,TimeUnit.SECONDS);} catch(InterruptedException ex) {Thread.currentThread().interrupt();}}
}
