package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Set;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 下载只读取已登记产物并重新签名，不触发重新制作或增加供应商费用。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class ExecutionDownloads {
    private static final Set<String> FILES=Set.of("video.mp4","subtitles.srt","cover.png","publish-package.zip");
    private final ExecutionService service;
    private final ExecutionMapper executions;
    private final GeneratedDraftMapper drafts;
    private final CreationPackageMapper packages;
    private final MediaUploadMapper media;
    private final ObjectStorageService storage;
    private final StoryboardCodec codec;
    public ExecutionDownloads(ExecutionService service,ExecutionMapper executions,GeneratedDraftMapper drafts,
            CreationPackageMapper packages,MediaUploadMapper media,ObjectStorageService storage,StoryboardCodec codec) {
        this.service=service;this.executions=executions;this.drafts=drafts;this.packages=packages;this.media=media;this.storage=storage;this.codec=codec;
    }
    public record Download(String fileName,String label,String versionId,String url,Instant expiresAt) { }
    public Download download(String task,String owner,String id,String fileName) {
        if(!FILES.contains(fileName))throw conflict("不支持的导出文件");
        var e=service.require(task,owner,id);
        String bucket,key,version=null,label;
        long bytes;
        if(Set.of("SAMPLE","DRAFT").contains(e.kind())) {
            if(!"COMPLETED".equals(e.status()) || "publish-package.zip".equals(fileName))throw conflict("样片尚未完成，或请求了不属于样片的正式包");
            var object=executions.outputs(id).stream().filter(o->fileName.equals(o.fileName())).findFirst().orElseThrow(()->conflict("样片没有该产物"));
            bucket=object.bucketName();key=object.objectKey();bytes=object.bytes();label="DRAFT".equals(e.kind())?"完整初稿":"样片";
        } else {
            if(e.currentRunId()==null)throw conflict("整片尚未开始渲染");
            var attempt=drafts.registered(task,owner,e.currentRunId()).orElseThrow(()->conflict("本次整片尚无可导出的媒体"));
            var draft=media.findDraftVideoByVersion(task,owner,attempt.versionId()).orElseThrow(()->conflict("成片版本不存在"));
            if(draft.mediaDeletedAt()!=null)throw conflict("该版本的媒体已删除");
            version=attempt.versionId();bucket=attempt.bucketName();label="制作预览";
            if("publish-package.zip".equals(fileName)) {
                var pkg=packages.find(e.currentRunId()).orElseThrow(()->conflict("该版本的正式包尚未生成"));
                if(!"READY".equals(pkg.status()) || !version.equals(pkg.versionId()))throw conflict("正式包尚未就绪或版本不一致");
                bucket=pkg.bucketName();key=pkg.objectKey();bytes=pkg.bytes();label="正式发布包";
            } else {
                var object=java.util.stream.StreamSupport.stream(codec.read(attempt.objectsJson()).spliterator(),false)
                        .filter(o->fileName.equals(o.path("name").asText())).findFirst().orElseThrow(()->conflict("该版本没有请求的产物"));
                key=object.path("objectKey").asText();bytes=object.path("bytes").asLong();
            }
        }
        if(storage.headObject(bucket,key).contentLength()!=bytes)throw conflict("存储产物与登记记录不一致，已停止下载");
        var signed=storage.presignBrowserGetObject(bucket,key,Duration.ofMinutes(5));
        return new Download(fileName,label,version,signed.url(),signed.expiresAt());
    }
}
