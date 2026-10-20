package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AssetMediaPreparerTest {
    @TempDir Path directory;

    private AssetMediaPreparer preparer() {
        FixedProjectRendererProperties properties = new FixedProjectRendererProperties();
        properties.setFfmpegPath("not-needed-for-rejected-input");
        properties.setFfprobePath("not-needed-for-rejected-input");
        return new AssetMediaPreparer(new ObjectMapper(), properties);
    }

    @Test void rejectsPlaylistBeforeLaunchingNativeTool() throws Exception {
        Path source = directory.resolve("upload.mp4");
        Files.writeString(source, "#EXTM3U\n#EXTINF:1,\nhttps://example.invalid/private.mp4\n");
        Path output = Files.createDirectory(directory.resolve("output"));
        var failure = assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "VIDEO_CLIP", () -> false));
        assertEquals("UNSUPPORTED_MEDIA", failure.code());
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        assertTrue(Files.exists(source));
    }

    @Test void rejectsOversizedSparseSourceBeforeNativeDecode() throws Exception {
        Path source = directory.resolve("large.mp4");
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(AssetMediaPreparer.MAX_SOURCE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        Path output = Files.createDirectory(directory.resolve("output"));
        var failure = assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "VIDEO_CLIP", () -> false));
        assertEquals("LIMIT_EXCEEDED", failure.code());
    }

    @Test void cancellationDoesNotReadOrWriteTheSource() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        var failure = assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(directory.resolve("not-existing.mp4"), output, "VIDEO_CLIP", () -> true));
        assertEquals("CANCELLED", failure.code());
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsInvalidKindAndTimeoutConfiguration() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        assertEquals("INVALID_MEDIA", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(directory.resolve("source.mp4"), output, "SUBTITLE", () -> false)).code());
        FixedProjectRendererProperties properties = new FixedProjectRendererProperties();
        properties.setTimeout(Duration.ZERO);
        assertEquals("ENVIRONMENT_UNAVAILABLE", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> new AssetMediaPreparer(new ObjectMapper(), properties).prepare(directory.resolve("source.mp4"), output, "VIDEO_CLIP", () -> false)).code());
    }

    @Test void existingOutputIsPreservedWhenPreparationIsRejected() throws Exception {
        Path source = directory.resolve("source.png"); Files.write(source, new byte[]{1});
        Path output = Files.createDirectory(directory.resolve("output"));
        Path previous = output.resolve("normalized.png"); Files.writeString(previous, "keep-existing-content");
        assertEquals("INVALID_MEDIA", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "IMAGE", () -> false)).code());
        assertEquals("keep-existing-content", Files.readString(previous));
    }
}
