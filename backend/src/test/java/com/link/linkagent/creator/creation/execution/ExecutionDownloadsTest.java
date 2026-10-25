package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 下载只签已有的精确版本，不能从当前版本指针猜产物或触发新制作。 */
class ExecutionDownloadsTest {
    private final ExecutionService service=mock(ExecutionService.class);
    private final ExecutionMapper executions=mock(ExecutionMapper.class);
    private final GeneratedDraftMapper drafts=mock(GeneratedDraftMapper.class);
    private final CreationPackageMapper packages=mock(CreationPackageMapper.class);
    private final MediaUploadMapper media=mock(MediaUploadMapper.class);
    private final ObjectStorageService storage=mock(ObjectStorageService.class);
    private final Execution execution=mock(Execution.class);
    private final ExecutionDownloads downloads=new ExecutionDownloads(service,executions,drafts,packages,media,storage,mock(StoryboardCodec.class));
    @BeforeEach void setup(){when(service.require("task","default","execution")).thenReturn(execution);}
    @Test void repeatedSampleReadsRefreshTheSignatureWithoutStartingOrRegisteringAVersion(){
        when(execution.kind()).thenReturn("SAMPLE");when(execution.status()).thenReturn("COMPLETED");
        when(executions.outputs("execution")).thenReturn(List.of(new Output("execution",1,"video.mp4","private-bucket","sample/video.mp4",100,"a".repeat(64),"video/mp4",true,false)));
        when(storage.headObject("private-bucket","sample/video.mp4")).thenReturn(new StoredObjectMetadata(100,"video/mp4","etag"));
        when(storage.presignBrowserGetObject("private-bucket","sample/video.mp4",Duration.ofMinutes(5)))
            .thenReturn(new PresignedObjectRead("https://private.example.invalid/first",Instant.now().plusSeconds(300)),new PresignedObjectRead("https://private.example.invalid/second",Instant.now().plusSeconds(300)));
        var first=downloads.download("task","default","execution","video.mp4");var second=downloads.download("task","default","execution","video.mp4");
        assertThat(first.versionId()).isNull();assertThat(second.label()).isEqualTo("样片");assertThat(first.url()).isNotEqualTo(second.url());
        verify(service,times(2)).require("task","default","execution");verifyNoMoreInteractions(service);verifyNoInteractions(drafts,packages,media);
    }
    @Test void unfinishedSamplesFormalSamplePackagesAndUnknownFileNamesAreRejected(){
        when(execution.kind()).thenReturn("SAMPLE");when(execution.status()).thenReturn("ACTIVE");
        assertThatThrownBy(()->downloads.download("task","default","execution","video.mp4")).isInstanceOf(ResponseStatusException.class);
        when(execution.status()).thenReturn("COMPLETED");
        assertThatThrownBy(()->downloads.download("task","default","execution","publish-package.zip")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->downloads.download("task","default","execution","../private.key")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage,drafts,packages,media);
    }
    @Test void fullPackageMustMatchTheRegisteredVersionAndDeletedMediaCannotBeRead(){
        when(execution.kind()).thenReturn("FULL");when(execution.currentRunId()).thenReturn("run");
        var attempt=mock(GeneratedDraftAttempt.class);when(attempt.versionId()).thenReturn("version-1");when(attempt.bucketName()).thenReturn("private-bucket");
        when(drafts.registered("task","default","run")).thenReturn(Optional.of(attempt));
        var draft=mock(DraftVideoRecord.class);when(media.findDraftVideoByVersion("task","default","version-1")).thenReturn(Optional.of(draft));
        var pkg=mock(CreationPackageRecord.class);when(pkg.status()).thenReturn("READY");when(pkg.versionId()).thenReturn("version-2");when(packages.find("run")).thenReturn(Optional.of(pkg));
        assertThatThrownBy(()->downloads.download("task","default","execution","publish-package.zip")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage);
        when(pkg.versionId()).thenReturn("version-1");when(pkg.bucketName()).thenReturn("private-bucket");when(pkg.objectKey()).thenReturn("package.zip");when(pkg.bytes()).thenReturn(200L);
        when(storage.headObject("private-bucket","package.zip")).thenReturn(new StoredObjectMetadata(200,"application/zip","etag"));
        when(storage.presignBrowserGetObject("private-bucket","package.zip",Duration.ofMinutes(5))).thenReturn(new PresignedObjectRead("https://private.example.invalid/package",Instant.now().plusSeconds(300)));
        assertThat(downloads.download("task","default","execution","publish-package.zip").versionId()).isEqualTo("version-1");
        when(draft.mediaDeletedAt()).thenReturn(LocalDateTime.now());
        assertThatThrownBy(()->downloads.download("task","default","execution","publish-package.zip")).isInstanceOf(ResponseStatusException.class);
        verify(storage,times(1)).presignBrowserGetObject("private-bucket","package.zip",Duration.ofMinutes(5));
    }
}
