package com.link.linkagent.knowledge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnowledgeReferenceFetchServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldResolveRelativeScriptPathInsideJarFileSystem() throws IOException {
        Path archivePath = temporaryDirectory.resolve("application.jar");
        try (FileSystem jarFileSystem = FileSystems.newFileSystem(archivePath, Map.of("create", "true"))) {
            Path jarRootPath = jarFileSystem.getPath("/");
            String relativeScriptPath = "scripts/bilibili_reference_fetcher.py";

            // 模拟 fat JAR 类加载位置参与项目根探测时，脚本相对路径仍由该文件系统自行解析。
            assertThatCode(() -> KnowledgeReferenceFetchService.resolveRelativePath(
                    jarRootPath,
                    relativeScriptPath
            )).doesNotThrowAnyException();

            Path resolvedPath = KnowledgeReferenceFetchService.resolveRelativePath(
                    jarRootPath,
                    relativeScriptPath
            );
            assertThat(resolvedPath.getFileSystem()).isSameAs(jarFileSystem);
            assertThat(resolvedPath.toString()).isEqualTo("/scripts/bilibili_reference_fetcher.py");
        }
    }
}
