package com.link.linkagent.creator.creation.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 确认前先检查本地工具，避免已付费生成素材后才发现渲染器根本无法启动。 */
@Component
public class RendererEnvironmentCheck {
    private final FixedProjectRendererProperties properties;
    private final ObjectMapper json;
    private long expires;
    private String cached;
    public RendererEnvironmentCheck(FixedProjectRendererProperties properties,ObjectMapper json){this.properties=properties;this.json=json;}
    public synchronized String problem(){
        if(System.nanoTime()<expires)return cached;
        cached=check();expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);return cached;
    }
    private String check(){
        Process process=null;Thread reader=null;
        var capture=new java.io.ByteArrayOutputStream();var children=new HashSet<ProcessHandle>();
        try {
            if(!properties.isEnabled())return "本地渲染器尚未启用";
            if(properties.getTimeout()==null || properties.getTimeout().isNegative() || properties.getTimeout().isZero()
                    || properties.getTimeout().compareTo(java.time.Duration.ofHours(1))>0)return "本地渲染超时配置须在一小时以内";
            Path module=Path.of(properties.getModuleRoot()).toRealPath();
            if(!Files.isRegularFile(module.resolve("linkagent_renderer/__main__.py")))return "未安装固定工程渲染模块";
            if(!Files.isRegularFile(Path.of(properties.getFontPath())))return "本地渲染器未配置可用中文字体";
            Path root=Path.of(properties.getWorkRoot()).toAbsolutePath().normalize();
            while(root!=null && !Files.exists(root))root=root.getParent();
            if(root==null || !Files.isDirectory(root) || !Files.isWritable(root))return "本地渲染工作目录不可写";
            var builder=new ProcessBuilder(properties.getPythonPath(),"-m","linkagent_renderer","check-env","--font",properties.getFontPath(),
                    "--ffmpeg",properties.getFfmpegPath(),"--ffprobe",properties.getFfprobePath()).directory(module.toFile()).redirectErrorStream(true);
            builder.environment().keySet().removeIf(k->!Set.of("PATH","SYSTEMROOT","WINDIR","TEMP","TMP","LANG","LD_LIBRARY_PATH").contains(k.toUpperCase(Locale.ROOT)));
            builder.environment().put("PYTHONDONTWRITEBYTECODE","1");builder.environment().put("PYTHONNOUSERSITE","1");
            process=builder.start();process.getOutputStream().close();Process launched=process;
            reader=Thread.ofVirtual().start(()->{try(var stream=launched.getInputStream()){
                byte[] data=new byte[1024];int n;while((n=stream.read(data))!=-1){int left=8192-capture.size();if(left>0)capture.write(data,0,Math.min(left,n));}
            }catch(java.io.IOException ignored){ }});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(process.isAlive() && System.nanoTime()<end){process.descendants().forEach(children::add);process.waitFor(100,TimeUnit.MILLISECONDS);}
            if(process.isAlive())return "本地渲染环境检查超时，请核对 Python、字体和 FFmpeg 配置";
            reader.join(500);
            if(reader.isAlive())return "本地渲染环境检查未完整退出";
            String output=capture.toString(StandardCharsets.UTF_8).strip();int line=output.lastIndexOf('\n');
            var result=json.readTree(output.substring(line+1));
            if(process.exitValue()==0 && "READY".equals(result.path("status").asText()))return null;
            return "本地渲染依赖未就绪，请核对 Python 依赖、中文字体和 FFmpeg 编码器";
        }catch(InterruptedException error){Thread.currentThread().interrupt();return "本地渲染环境检查已中断";}
        catch(Exception error){return "无法检查本地渲染环境，请核对 Python、字体和 FFmpeg 配置";}
        finally {if(process!=null){process.descendants().forEach(children::add);children.forEach(child->{if(child.isAlive())child.destroyForcibly();});if(process.isAlive())process.destroyForcibly();}if(reader!=null && reader.isAlive())reader.interrupt();}
    }
}
