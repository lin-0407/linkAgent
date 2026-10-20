package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 协议替身验证失租和关闭分支；真实领取、恢复、存储和渲染由隔离集成测试覆盖。 */
class FixedProjectWorkerTest {
    final CreationWorkerProperties properties = new CreationWorkerProperties();
    final FixedProjectJobMapper jobs = mock(FixedProjectJobMapper.class);
    final CreationRuntimeService runtime = mock(CreationRuntimeService.class);
    final FixedProjectJobFiles files = mock(FixedProjectJobFiles.class);
    final FixedProjectRenderService renderer = mock(FixedProjectRenderService.class);
    final GeneratedDraftService drafts = mock(GeneratedDraftService.class);
    final GeneratedDraftPersistenceService persistence = mock(GeneratedDraftPersistenceService.class);
    final GeneratedDraftProbeService probe = mock(GeneratedDraftProbeService.class);
    final Run run = mock(Run.class);
    final FixedProjectJob job = new FixedProjectJob("run", "task", "default", "source", "{}");
    final FixedProjectRenderService.Request request = new FixedProjectRenderService.Request(Path.of("input.json"), "run", "attempt", "board", "a".repeat(64));

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.setLeaseSeconds(5); properties.setHeartbeatSeconds(1);
        when(run.taskId()).thenReturn("task"); when(run.ownerId()).thenReturn("default"); when(run.runId()).thenReturn("run");
        when(run.leaseGeneration()).thenReturn(1L); when(run.attemptCount()).thenReturn(1); when(run.status()).thenReturn("RUNNING");
        when(jobs.candidates()).thenReturn(List.of(job));
        when(runtime.claim(eq("task"), eq("default"), eq("run"), anyString(), eq(5))).thenReturn(Optional.of(run));
        when(runtime.heartbeat(eq("task"), eq("default"), eq("run"), anyString(), eq(1L), eq(5))).thenReturn(true);
        when(runtime.getRun("task", "default", "run")).thenReturn(view("RUNNING"));
        when(files.prepare(eq(job), eq(run), any())).thenReturn(request);
    }

    @Test void disabledWorkerNeverScansOrClaims() {
        properties.setEnabled(false);
        try (var worker = worker()) { worker.poll(); }
        verifyNoInteractions(jobs, runtime, renderer, drafts);
    }

    @Test void cancellationClaimDoesNotReadAnyLocalFiles() throws Exception {
        when(run.status()).thenReturn("CANCEL_REQUESTED"); CountDownLatch ended = new CountDownLatch(1);
        when(runtime.acknowledgeCancellation(eq("task"), eq("default"), eq("run"), anyString(), eq(1L)))
                .thenAnswer(call -> { ended.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue(); }
        verifyNoInteractions(files, renderer, drafts, persistence);
    }

    @Test void exhaustedTakeoverDoesNotStartAnotherRender() throws Exception {
        when(run.attemptCount()).thenReturn(4); CountDownLatch ended = new CountDownLatch(1);
        when(runtime.fail(eq("task"), eq("default"), eq("run"), anyString(), eq(1L), eq("ATTEMPTS_EXHAUSTED")))
                .thenAnswer(call -> { ended.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue(); }
        verifyNoInteractions(files, renderer, drafts);
        verifyNoInteractions(probe);
    }

    @Test void registeredVersionRecoveryDoesNotRequireInputDirectory() throws Exception {
        when(run.attemptCount()).thenReturn(8);
        var draft = mock(DraftVideoRecord.class); when(draft.versionId()).thenReturn("version");
        when(persistence.registeredResult(any())).thenReturn(Optional.of(draft)); CountDownLatch ended = new CountDownLatch(1);
        when(runtime.waitForDependency(eq("task"), eq("default"), eq("run"), anyString(), eq(1L), eq("version")))
                .thenAnswer(call -> { ended.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue(); }
        verifyNoInteractions(files, renderer, drafts);
        verify(probe).probe(any(), eq("version"), any());
        verify(runtime, never()).complete(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void failedSourceProbeKeepsRegisteredVersionButDoesNotWaitForUnreadyMedia() throws Exception {
        var draft = mock(DraftVideoRecord.class); when(draft.versionId()).thenReturn("version");
        when(persistence.registeredResult(any())).thenReturn(Optional.of(draft));
        when(probe.probe(any(), eq("version"), any())).thenThrow(new RenderExecutionException("SOURCE_PROBE_FAILED", "探测失败"));
        CountDownLatch ended = new CountDownLatch(1);
        when(runtime.fail(eq("task"), eq("default"), eq("run"), anyString(), eq(1L), eq("SOURCE_PROBE_FAILED")))
                .thenAnswer(call -> { ended.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue(); }
        verifyNoInteractions(files, renderer, drafts);
        verify(runtime, never()).waitForDependency(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void heartbeatFailureStopsRenderWithoutWritingFailedWithOldLease() throws Exception {
        when(runtime.heartbeat(eq("task"), eq("default"), eq("run"), anyString(), eq(1L), eq(5))).thenReturn(false);
        CountDownLatch stopped = new CountDownLatch(1);
        when(renderer.render(eq(request), any())).thenAnswer(call -> {
            BooleanSupplier cancelled = call.getArgument(1); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!cancelled.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(cancelled.getAsBoolean()).isTrue(); stopped.countDown();
            throw new RenderExecutionException("CANCELLED", "租约失效");
        });
        try (var worker = worker()) { worker.poll(); assertThat(stopped.await(4, TimeUnit.SECONDS)).isTrue(); }
        verifyNoInteractions(drafts);
        verify(runtime, never()).fail(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void shutdownInterruptsOwnExecutionAndLeavesRunForRecovery() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        when(renderer.render(eq(request), any())).thenAnswer(call -> {
            started.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("不应正常结束"); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new RenderExecutionException("CANCELLED", "执行器关闭"); }
        });
        try (var worker = worker()) { worker.poll(); assertThat(started.await(3, TimeUnit.SECONDS)).isTrue(); }
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(drafts);
        verify(runtime, never()).fail(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(runtime, never()).acknowledgeCancellation(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test void invalidHeartbeatCannotBeConfiguredLongerThanSafeLeaseWindow() {
        properties.setHeartbeatSeconds(3);
        assertThatThrownBy(this::worker).isInstanceOf(IllegalStateException.class);
    }

    private FixedProjectWorker worker() { return new FixedProjectWorker(properties, jobs, runtime, files, renderer, drafts, persistence, probe); }
    private RunView view(String state) { return new RunView("run", "FULL", state, state, null, 1, 1, null, null, null); }
}
