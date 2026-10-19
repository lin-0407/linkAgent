package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 服务端上传完整渲染包，实际对象往返核验后才能进入版本登记事务。 */
@Service
@ConditionalOnProperty(name = {"creator.media.enabled", "creator.creation.renderer.enabled"}, havingValue = "true")
public class GeneratedDraftService {
    private final FixedProjectRenderService renderer;
    private final GeneratedDraftPersistenceService persistence;
    private final ObjectStorageService storage;
    private final ObjectStorageProperties storageProperties;
    private final ObjectMapper json;

    public GeneratedDraftService(FixedProjectRenderService renderer, GeneratedDraftPersistenceService persistence,
            ObjectStorageService storage, ObjectStorageProperties storageProperties, ObjectMapper json) {
        this.renderer = renderer; this.persistence = persistence; this.storage = storage;
        this.storageProperties = storageProperties; this.json = json;
    }

    @Transactional(propagation = Propagation.NEVER)
    public DraftVideoRecord registerGeneratedDraft(GeneratedDraftLease lease, FixedProjectRenderService.Request request,
                                                    BooleanSupplier cancelled) {
        if (!lease.runId().equals(request.runId())) throw conflict("工程不属于本次制作运行");
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw conflict("制作已取消");
            persistence.checkLive(lease);
        };
        check.run();
        var restored = persistence.restoreRegistered(lease, request.attemptId(), request.inputHash());
        if (restored.isPresent()) return restored.get();
        storageProperties.validateConfigured();
        var verified = renderer.verifyExisting(request, cancelled);
        var artifacts = verified.artifacts();
        var files = artifacts.files().entrySet().stream().map(entry -> new GeneratedDraftFile(entry.getKey(),
                entry.getValue().contentType(), entry.getValue().bytes(), entry.getValue().sha256())).toList();
        var attempt = persistence.prepare(lease, request.attemptId(), request.inputHash(),
                renderContentHash(json, verified.projectJson()), request.storyboardId(), storageProperties.getBucket(), files);
        if ("REGISTERED".equals(attempt.status())) return persistence.register(lease, attempt.attemptId());
        for (var object : persistence.objects(attempt)) {
            check.run();
            Path source = artifacts.files().get(object.name()).path();
            if (!object.sha256().equals(digest(source, cancelled))) throw conflict("上传前本地产物已变化");
            storage.putObject(attempt.bucketName(), object.objectKey(), source, object.contentType());
            check.run();
            var head = storage.headObject(attempt.bucketName(), object.objectKey());
            if (head.contentLength() != object.bytes() || !object.contentType().equalsIgnoreCase(head.contentType()))
                throw conflict("私有对象长度或媒体类型与产物不符");
            // ETag 不是跨厂商通用的 SHA-256，必须回读实际对象验证内容。
            Path downloaded;
            try { downloaded = Files.createTempFile(artifacts.directory().getParent(), "object-check-", ".tmp"); Files.delete(downloaded); }
            catch (IOException e) { throw conflict("无法创建对象校验临时文件"); }
            try {
                storage.downloadObject(attempt.bucketName(), object.objectKey(), downloaded);
                if (Files.size(downloaded) != object.bytes() || !object.sha256().equals(digest(downloaded, cancelled)))
                    throw conflict("私有对象内容校验不通过");
            } catch (IOException e) { throw conflict("无法读取对象校验结果"); }
            finally {
                try { Files.deleteIfExists(downloaded); }
                catch (IOException e) { throw conflict("对象校验临时文件无法清理"); }
            }
            check.run();
        }
        // 上传成功仅是里程碑，后续必须真实执行探测、媒体预处理和完整试映。
        check.run();
        return persistence.register(lease, attempt.attemptId());
    }

    private String digest(Path path, BooleanSupplier cancelled) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int size;
                while ((size = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw conflict("制作已取消");
                    digest.update(buffer, 0, size);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) { throw conflict("无法读取产物内容"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
