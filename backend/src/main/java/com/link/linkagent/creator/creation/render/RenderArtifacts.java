package com.link.linkagent.creator.creation.render;

import java.nio.file.Path;
import java.util.Map;

/** 本地技术验收结果；此对象不表示业务运行完成或试映通过。 */
public record RenderArtifacts(Path directory, Map<String, Artifact> files, String inputHash,
                              String rendererVersion, long durationMs, int width, int height,
                              int fps, int frameCount) {
    public RenderArtifacts { files = Map.copyOf(files); }

    public record Artifact(Path path, long bytes, String sha256, String contentType) { }
}
