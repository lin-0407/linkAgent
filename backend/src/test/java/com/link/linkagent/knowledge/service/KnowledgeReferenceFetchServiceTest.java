package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void shouldBuildLightweightMetadataCommandWithoutCommentsOrDanmaku() {
        List<String> command = KnowledgeReferenceFetchService.buildReferenceScriptCommand(
                Path.of("scripts/bilibili_reference_fetcher.py"),
                Path.of("export/bilibili_reference"),
                "BV1xx411c7mD",
                null,
                null,
                0,
                0
        );

        assertThat(command).containsSubsequence("--max-comments", "0");
        assertThat(command).containsSubsequence("--max-danmaku", "0");
    }

    @Test
    void shouldRefreshMetadataOnlyAfterScriptReturnsCompletePayload() {
        KnowledgeReferenceVideoService videoService = mock(KnowledgeReferenceVideoService.class);
        KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
        KnowledgeReferenceFetchService service = spy(
                new KnowledgeReferenceFetchService(videoService, mapper, new ObjectMapper()));
        ReferenceVideoRecord record = referenceRecord("BV1xx411c7mD");
        ReferenceVideoImportRequest.VideoStats stats = new ReferenceVideoImportRequest.VideoStats(
                100L, 20L, 5L, 8L, 3L, 6L);
        ReferenceVideoImportRequest.VideoItem metadata = new ReferenceVideoImportRequest.VideoItem(
                "BV1xx411c7mD",
                "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "测试视频",
                null,
                null,
                "知识",
                null,
                stats,
                List.of(),
                List.of()
        );
        ReferenceVideoRecord responseRecord = referenceRecord("BV1xx411c7mD");
        responseRecord.setCoverUrl(metadata.coverUrl());
        ReferenceVideoResponse expected = ReferenceVideoResponse.from(responseRecord);
        when(mapper.findByVideoId("video-1")).thenReturn(Optional.of(record));
        doReturn(metadata).when(service).fetchPublicMetadata("BV1xx411c7mD");
        when(videoService.updatePublicMetadata("video-1", metadata.coverUrl(), stats)).thenReturn(expected);

        ReferenceVideoResponse actual = service.refreshPublicMetadata("video-1");

        assertThat(actual).isSameAs(expected);
        verify(videoService).updatePublicMetadata("video-1", metadata.coverUrl(), stats);
    }

    @Test
    void shouldKeepDatabaseUntouchedWhenScriptFails() {
        KnowledgeReferenceVideoService videoService = mock(KnowledgeReferenceVideoService.class);
        KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
        KnowledgeReferenceFetchService service = spy(
                new KnowledgeReferenceFetchService(videoService, mapper, new ObjectMapper()));
        when(mapper.findByVideoId("video-1")).thenReturn(Optional.of(referenceRecord("BV1xx411c7mD")));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "脚本失败"))
                .when(service).fetchPublicMetadata("BV1xx411c7mD");

        assertThatThrownBy(() -> service.refreshPublicMetadata("video-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verifyNoInteractions(videoService);
    }

    @Test
    void shouldRejectSeedWithoutBvBeforeRunningScript() {
        KnowledgeReferenceVideoService videoService = mock(KnowledgeReferenceVideoService.class);
        KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
        KnowledgeReferenceFetchService service = spy(
                new KnowledgeReferenceFetchService(videoService, mapper, new ObjectMapper()));
        when(mapper.findByVideoId("video-1")).thenReturn(Optional.of(referenceRecord(null)));

        assertThatThrownBy(() -> service.refreshPublicMetadata("video-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(service, never()).fetchPublicMetadata("BV1xx411c7mD");
        verifyNoInteractions(videoService);
    }

    private ReferenceVideoRecord referenceRecord(String bvId) {
        ReferenceVideoRecord record = new ReferenceVideoRecord();
        record.setVideoId("video-1");
        record.setBvId(bvId);
        return record;
    }
}
