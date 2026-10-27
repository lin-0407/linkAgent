package com.link.linkagent.creator.creation.render;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 仅部署者配置可执行程序；工程 JSON 和页面请求不能携带命令或参数模板。 */
@Component
@ConfigurationProperties(prefix = "creator.creation.renderer")
public class FixedProjectRendererProperties {
    private boolean enabled;
    private String pythonPath = "python3";
    private String moduleRoot = "/opt/renderer";
    private String workRoot = "/tmp/linkagent-creation";
    private String fontPath = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc";
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private Duration timeout = Duration.ofSeconds(600);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPythonPath() { return pythonPath; }
    public void setPythonPath(String value) { pythonPath = value; }
    public String getModuleRoot() { return moduleRoot; }
    public void setModuleRoot(String value) { moduleRoot = value; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String value) { workRoot = value; }
    public String getFontPath() { return fontPath; }
    public void setFontPath(String value) { fontPath = value; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String value) { ffmpegPath = value; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String value) { ffprobePath = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
}
