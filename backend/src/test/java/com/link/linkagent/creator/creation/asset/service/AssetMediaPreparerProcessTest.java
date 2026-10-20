package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 用本地包装进程验证超时和回收边界；不能替代真正媒体用例。 */
@EnabledOnOs(OS.LINUX)
class AssetMediaPreparerProcessTest {
    @TempDir Path directory;

    @Test void timeoutReapsExactRootAndDescendantProcesses() throws Exception {
        Path pids = directory.resolve("pids");
        Path script = script("probe.sh", "echo $$ > '" + pids + "'\nsleep 30 &\necho $! >> '" + pids + "'\nwait\n");
        var preparer = preparer(script, script, Duration.ofMillis(400));
        Path output = Files.createDirectory(directory.resolve("output"));
        long started = System.nanoTime();
        assertEquals("TIMEOUT", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer.prepare(source(), output, "MUSIC", () -> false)).code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
        assertStopped(pids);
        try (var paths = Files.list(output)) { assertEquals(0, paths.count()); }
    }

    @Test void cancellationReapsExactRootAndDescendantProcesses() throws Exception {
        Path pids = directory.resolve("pids");
        Path script = script("probe.sh", "echo $$ > '" + pids + "'\nsleep 30 &\necho $! >> '" + pids + "'\nwait\n");
        var preparer = preparer(script, script, Duration.ofSeconds(10));
        Path output = Files.createDirectory(directory.resolve("output"));
        assertEquals("CANCELLED", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer.prepare(source(), output, "NARRATION", () -> {
                    try { return Files.exists(pids) && Files.readAllLines(pids).size() == 2; }
                    catch (Exception e) { return false; }
                })).code());
        assertStopped(pids);
    }

    @Test void excessiveProbeOutputIsBoundedAndStopsEarly() throws Exception {
        Path script = script("probe.sh", "head -c 1048576 /dev/zero\n");
        Path output = Files.createDirectory(directory.resolve("output"));
        assertEquals("LIMIT_EXCEEDED", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer(script, script, Duration.ofSeconds(5)).prepare(source(), output, "MUSIC", () -> false)).code());
        try (var paths = Files.list(output)) { assertEquals(0, paths.count()); }
    }

    @Test void processReceivesOnlySystemEnvironment() throws Exception {
        Path audit = directory.resolve("environment");
        Path script = script("probe.sh", "env > '" + audit + "'\nexit 1\n");
        Path output = Files.createDirectory(directory.resolve("output"));
        assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer(script, script, Duration.ofSeconds(5)).prepare(source(), output, "MUSIC", () -> false));
        Set<String> permitted = Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LD_LIBRARY_PATH", "LC_ALL", "PWD", "SHLVL", "_");
        for (String line : Files.readAllLines(audit)) assertTrue(permitted.contains(line.substring(0, line.indexOf('='))), line.substring(0, line.indexOf('=')));
    }

    private void assertStopped(Path pids) throws Exception {
        assertTrue(Files.exists(pids));
        assertEquals(2, Files.readAllLines(pids).size());
        for (String pid : Files.readAllLines(pids)) assertFalse(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false), "本次媒体子进程未被回收");
    }
    private Path source() throws Exception {
        Path source = directory.resolve("source.wav");
        byte[] bytes = new byte[32];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        Files.write(source, bytes); return source;
    }
    private Path script(String name, String body) throws Exception {
        Path script = directory.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
    private AssetMediaPreparer preparer(Path probe, Path ffmpeg, Duration timeout) {
        FixedProjectRendererProperties properties = new FixedProjectRendererProperties();
        properties.setFfprobePath(probe.toString()); properties.setFfmpegPath(ffmpeg.toString()); properties.setTimeout(timeout);
        return new AssetMediaPreparer(new ObjectMapper(), properties);
    }
}
