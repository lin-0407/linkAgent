package com.link.linkagent.creator.media.probe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/** 使用真实 FFprobe 等待本轮隔离 HTTP 源，确认取消、线程中断和超时都回收自身进程。 */
@EnabledIfEnvironmentVariable(named = "LINKAGENT_RENDERER_NATIVE_TESTS", matches = "true")
class FfprobeMediaProbeCancellationNativeTest {
    @TempDir Path root;

    @Test
    @EnabledOnOs(OS.LINUX)
    void exitedWrapperCannotLeaveObservedPipeWriterRunning() throws Exception {
        Path wrapper = root.resolve("probe-wrapper");
        Path childPid = root.resolve("probe-wrapper.child.pid");
        Files.writeString(wrapper, "#!/bin/sh\nsleep 30 &\nchild=$!\nprintf '%s' \"$child\" > \"$0.child.pid\"\nsleep 0.4\nexit 0\n");
        Files.setPosixFilePermissions(wrapper, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        var properties = new CreatorMediaProperties(); properties.setEnabled(true);
        properties.getProcessing().setFfprobePath(wrapper.toString());
        properties.getProcessing().setProbeTimeout(Duration.ofSeconds(2));
        var service = new FfprobeMediaProbeService(properties, new ObjectMapper());
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> service.probe("http://fixture.invalid/ignored.mp4"))
                            .isInstanceOf(MediaProbeException.class));
            long pid = Long.parseLong(Files.readString(childPid));
            assertThat(isExecuting(pid)).isFalse();
            System.out.println("S3E_WRAPPER_PIPE_CLEANUP_OK observedChild=" + pid);
        } finally {
            if (Files.exists(childPid)) {
                long pid = Long.parseLong(Files.readString(childPid));
                if (isExecuting(pid)) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
            service.closeOutputReaderExecutor();
        }
    }

    private boolean isExecuting(long pid) throws Exception {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.exists(stat)) return false;
        // Linux 容器的 PID 1 可能延迟收割孤儿僵尸；Z 状态已释放管道且不再执行。
        String value;
        try { value = Files.readString(stat); }
        catch (java.nio.file.NoSuchFileException ignored) { return false; }
        return value.charAt(value.lastIndexOf(')') + 2) != 'Z';
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "INTERRUPT", "TIMEOUT"})
    void blockedSourceProbeMustStopItsActualProcess(String mode) throws Exception {
        var properties = new CreatorMediaProperties(); properties.setEnabled(true);
        properties.getProcessing().setProbeTimeout(Duration.ofSeconds("TIMEOUT".equals(mode) ? 2 : 20));
        var service = new FfprobeMediaProbeService(properties, new ObjectMapper());
        var requested = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(handlers);
        server.createContext("/blocked.mp4", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(0); exchange.getResponseBody().flush(); requested.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } finally { exchange.close(); }
        });
        server.start();
        var cancelled = new AtomicBoolean(); var thread = new AtomicReference<Thread>();
        Set<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        try (var caller = Executors.newSingleThreadExecutor()) {
            Future<?> future = caller.submit(() -> {
                thread.set(Thread.currentThread());
                return service.probe("http://127.0.0.1:" + server.getAddress().getPort() + "/blocked.mp4", cancelled::get);
            });
            assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
            var processes = ProcessHandle.current().descendants()
                    .filter(process -> !before.contains(process.pid()))
                    .filter(process -> process.info().command().orElse("").endsWith("ffprobe")).toList();
            assertThat(processes).hasSize(1);
            if ("CANCEL".equals(mode)) cancelled.set(true);
            else if ("INTERRUPT".equals(mode)) thread.get().interrupt();
            assertThatThrownBy(() -> future.get(7, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MediaProbeException.class);
            assertThat(processes).allMatch(process -> !process.isAlive());
            System.out.println("S3E_NATIVE_PROBE_STOP_OK mode=" + mode + " stopped=" + processes.size());
        } finally {
            release.countDown(); server.stop(0); handlers.close(); service.closeOutputReaderExecutor();
        }
    }
}
