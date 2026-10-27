package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.util.Comparator;

/** 只回收台账标明未采纳的对象和终止运行的受控工作目录，不触碰已登记成片及其历史素材。 */
@Service
@ConditionalOnProperty(name={"creator.media.enabled","creator.creation.renderer.enabled"},havingValue="true")
public class CreationResourceCleanup {
    private static final Logger log=LoggerFactory.getLogger(CreationResourceCleanup.class);
    private final CreationCleanupMapper mapper;
    private final FixedProjectRendererProperties properties;
    private final ObjectStorageService storage;
    private final StoryboardCodec codec;
    public CreationResourceCleanup(CreationCleanupMapper mapper,FixedProjectRendererProperties properties,ObjectStorageService storage,StoryboardCodec codec) {
        this.mapper=mapper;this.properties=properties;this.storage=storage;this.codec=codec;
    }
    public void clean() {
        for(var attempt:mapper.orphanedAttempts()) {
            try {
                String prefix="users/"+identifier(attempt.ownerId())+"/tasks/"+identifier(attempt.taskId())+"/";
                var objects=codec.read(attempt.objectsJson());
                if(!objects.isArray() || objects.size()>8)throw new IllegalArgumentException("失败产物清单无效");
                for(var object:objects) {
                    String key=object.path("objectKey").asText();
                    if(!key.startsWith(prefix) || key.contains(".."))throw new IllegalArgumentException("失败对象不属于该运行的任务");
                    if(mapper.references(attempt.bucketName(),key)==0)storage.deleteObject(attempt.bucketName(),key);
                }
                mapper.cleaned(attempt);
            } catch(RuntimeException error){log.info("未登记成片对象等待回收，运行={}，类型={}",attempt.runId(),error.getClass().getSimpleName());}
        }
        int grace=(int)Math.min(Integer.MAX_VALUE,Math.max(900,properties.getTimeout().toSeconds()+180));
        for(String run:mapper.finishedRuns(grace)) {
            try {cleanupLocal(run);mapper.localDone(run);}
            catch(IOException|RuntimeException error){log.info("终止运行工作目录等待回收，运行={}，类型={}",run,error.getClass().getSimpleName());}
        }
    }
    void cleanupLocal(String run)throws IOException {
        identifier(run);
        Path root=Path.of(properties.getWorkRoot()).toAbsolutePath().normalize();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        if(!root.toRealPath().equals(root))throw new IOException("渲染根目录包含重定向");
        Path target=root.resolve(run).normalize();
        deleteTree(root,target);
        // 配音和视频准备使用固定子目录及 UUID 运行前缀，异常退出时 finally 未必有机会回收。
        // 仅识别当前服务确实生成的临时目录格式，旧格式及不明目录保留给作者核对。
        if(run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            var pattern=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(run)+"-[A-Za-z0-9_-]{1,64}-[0-9]{1,20}");
            for(String family:java.util.List.of("voice","ai-video")) {
                Path parent=root.resolve(family);
                if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent))throw new IOException("素材准备目录包含重定向");
                try(var entries=Files.list(parent)) {
                    for(Path entry:entries.filter(p->pattern.matcher(p.getFileName().toString()).matches()).toList())deleteTree(parent,entry);
                }
            }
        }
    }
    private void deleteTree(Path root,Path target)throws IOException {
        if(!target.getParent().equals(root) || !target.startsWith(root))throw new IOException("回收目录越界");
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
        if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS) || !target.toRealPath().equals(target))throw new IOException("运行目录不是受控真实目录");
        try(var files=Files.walk(target)) {
            for(Path path:files.sorted(Comparator.reverseOrder()).toList()) {
                if(!path.toAbsolutePath().normalize().startsWith(target))throw new IOException("回收文件越界");
                Files.deleteIfExists(path);
            }
        }
    }
    private static String identifier(String id){if(id==null || !id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("回收身份无效");return id;}
}
