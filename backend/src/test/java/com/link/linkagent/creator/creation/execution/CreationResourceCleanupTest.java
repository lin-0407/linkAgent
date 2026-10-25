package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreationResourceCleanupTest {
    @TempDir Path root;
    @Test void cleanupRemovesOnlyTheSelectedRunAndRejectsTraversal()throws Exception {
        var properties=new FixedProjectRendererProperties();properties.setWorkRoot(root.toString());
        var selected=Files.createDirectories(root.resolve("run-one/attempt/assets"));Files.writeString(selected.resolve("part.bin"),"owned");
        Files.createDirectories(root.resolve("run-two"));Files.writeString(root.resolve("run-two/keep.txt"),"other run");
        var service=new CreationResourceCleanup(mock(CreationCleanupMapper.class),properties,mock(ObjectStorageService.class),null);
        service.cleanupLocal("run-one");assertThat(root.resolve("run-one")).doesNotExist();assertThat(root.resolve("run-two/keep.txt")).hasContent("other run");
        assertThatThrownBy(()->service.cleanupLocal("../run-two")).isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("run-two/keep.txt")).exists();
    }
    @Test void interruptedVoiceAndVideoPreparationAreCollectedByTheExactFinishedRunPrefix()throws Exception {
        String run=java.util.UUID.randomUUID().toString(),active=java.util.UUID.randomUUID().toString();
        var properties=new FixedProjectRendererProperties();properties.setWorkRoot(root.toString());
        for(String family:java.util.List.of("voice","ai-video")) {
            Path owned=Files.createDirectories(root.resolve(family).resolve(run+"-scene-1-123456789"));Files.writeString(owned.resolve("source.bin"),"临时素材");
            Files.createDirectories(root.resolve(family).resolve(active+"-scene-1-987654321"));
            Files.createDirectories(root.resolve(family).resolve(run+"-unrecognized-directory"));
        }
        var service=new CreationResourceCleanup(mock(CreationCleanupMapper.class),properties,mock(ObjectStorageService.class),null);
        service.cleanupLocal(run);
        for(String family:java.util.List.of("voice","ai-video")) {
            assertThat(root.resolve(family).resolve(run+"-scene-1-123456789")).doesNotExist();
            assertThat(root.resolve(family).resolve(active+"-scene-1-987654321")).exists();
            assertThat(root.resolve(family).resolve(run+"-unrecognized-directory")).exists();
        }
    }
}
