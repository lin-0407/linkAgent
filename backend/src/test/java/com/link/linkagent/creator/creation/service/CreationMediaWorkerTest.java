package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.mapper.CreationPackageMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationPackageRecord;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.RunView;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse;
import com.link.linkagent.creator.media.processing.service.MediaProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 协议替身验证闭环阶段推进；真实媒体与存储由隔离集成/原生测试覆盖。 */
class CreationMediaWorkerTest {
    final CreationMediaProperties properties = new CreationMediaProperties();
    final CreationMediaMapper links = mock(CreationMediaMapper.class);
    final CreationPackageMapper packages = mock(CreationPackageMapper.class);
    final GeneratedDraftMapper drafts = mock(GeneratedDraftMapper.class);
    final CreationRuntimeService runtime = mock(CreationRuntimeService.class);
    final MediaProcessingService processing = mock(MediaProcessingService.class);
    final PreflightReviewService preflight = mock(PreflightReviewService.class);
    final CreationPackageService packageService = mock(CreationPackageService.class);
    final Run run = mock(Run.class);

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.setLeaseSeconds(5); properties.setHeartbeatSeconds(1);
        when(run.taskId()).thenReturn("task"); when(run.ownerId()).thenReturn("default");
        when(run.runId()).thenReturn("run"); when(run.leaseGeneration()).thenReturn(2L);
    }

    @Test void disabledWorkerNeverScansOrClaims() {
        properties.setEnabled(false);
        try (var worker = worker()) { worker.poll(); }
        verifyNoInteractions(links, runtime, processing, preflight, packageService);
    }

    @Test void pendingStageCreatesOneProcessingJobAndWaitsForIt() throws Exception {
        given(link("MEDIA_PENDING", null, null), view("WAITING_DEPENDENCY", 4, 1));
        var job = job("job-1", "QUEUED");
        when(processing.createJob(any(), any(), any(), any(), any())).thenReturn(job);
        CountDownLatch waited = waitForDependency("job-1");
        try (var worker = worker()) { worker.poll(); assertThat(waited.await(3, TimeUnit.SECONDS)).isTrue(); }
        verify(processing).createJob(eq("default"), eq("task"), eq("version"), any(), any());
        verify(links).advance("run", "MEDIA_PENDING", "MEDIA_RUNNING", "job-1", null, null, 2L);
        verify(runtime, never()).completeProduction(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void completedProcessingStartsReviewAndDoesNotRepeatTheJob() throws Exception {
        given(link("MEDIA_RUNNING", "job-1", null), view("WAITING_DEPENDENCY", 6, 2));
        when(processing.getCurrentJob("default", "task", "version")).thenReturn(job("job-1", "COMPLETED"));
        when(preflight.create(any(), any(), any(), any(), any())).thenReturn(review("review-1", "QUEUED"));
        CountDownLatch waited = waitForDependency("review-1");
        try (var worker = worker()) { worker.poll(); assertThat(waited.await(3, TimeUnit.SECONDS)).isTrue(); }
        verify(processing, never()).createJob(any(), any(), any(), any(), any());
        verify(links).advance("run", "MEDIA_RUNNING", "PREFLIGHT_RUNNING", "job-1", "review-1", null, 2L);
    }

    @Test void failedReviewHoldsRunWithoutSpendingFurtherOrFailingIt() throws Exception {
        given(link("PREFLIGHT_RUNNING", "job-1", "review-1"), view("WAITING_DEPENDENCY", 8, 2));
        when(preflight.get("default", "task", "review-1")).thenReturn(review("review-1", "FAILED"));
        CountDownLatch held = new CountDownLatch(1);
        doAnswer(call -> { held.countDown(); return null; }).when(runtime)
                .holdForUser(eq("task"), eq("default"), eq("run"), anyString(), eq(2L), eq("PREFLIGHT_FAILED"));
        try (var worker = worker()) { worker.poll(); assertThat(held.await(3, TimeUnit.SECONDS)).isTrue(); }
        verify(links).advance("run", "PREFLIGHT_RUNNING", "FAILED", "job-1", "review-1",
                "PREFLIGHT_FAILED", 2L);
        verify(runtime, never()).completeProduction(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(runtime, never()).fail(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void readyPackageCompletesRunWithExactVersionFacts() throws Exception {
        given(link("PACKAGE_READY", "job-1", "review-1"), view("WAITING_DEPENDENCY", 10, 2));
        when(packages.find("run")).thenReturn(Optional.of(new CreationPackageRecord("run", "task", "default", "version",
                "job-1", "review-1", "READY", "bucket", "users/default/tasks/task/versions/version/package/publish-package.zip",
                2048L, "f".repeat(64), "{}", 2L, null)));
        when(preflight.get("default", "task", "review-1")).thenReturn(review("review-1", "COMPLETED"));
        CountDownLatch completed = new CountDownLatch(1);
        when(runtime.completeProduction(eq("task"), eq("default"), eq("run"), anyString(), eq(2L), anyString()))
                .thenAnswer(call -> { completed.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue(); }
        verify(runtime).completeProduction(eq("task"), eq("default"), eq("run"), anyString(), eq(2L),
                argThat(result -> result.contains("\"versionId\":\"version\"") && result.contains("\"published\":false")));
        verify(packageService, never()).ensure(any(), any(), any());
    }

    @Test void cancelRequestStopsReviewAndAcknowledgesWithoutClaimingCompletion() throws Exception {
        given(link("PREFLIGHT_RUNNING", "job-1", "review-1"), view("CANCEL_REQUESTED", 12, 2));
        CountDownLatch acked = new CountDownLatch(1);
        when(runtime.acknowledgeCancellation(eq("task"), eq("default"), eq("run"), anyString(), eq(2L)))
                .thenAnswer(call -> { acked.countDown(); return run; });
        try (var worker = worker()) { worker.poll(); assertThat(acked.await(3, TimeUnit.SECONDS)).isTrue(); }
        verify(preflight).cancel("default", "task", "review-1");
        verify(runtime, never()).completeProduction(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    private CreationMediaWorker worker() {
        return new CreationMediaWorker(properties, links, packages, drafts, runtime, processing, preflight, packageService,
                new CreationMediaService(links, drafts, runtime));
    }

    private void given(CreationMediaLink link, RunView view) {
        when(links.candidates()).thenReturn(List.of(link));
        when(links.find("run")).thenReturn(Optional.of(link));
        when(runtime.getRun("task", "default", "run")).thenReturn(view);
        when(runtime.wake(eq("task"), eq("default"), eq("run"), anyLong())).thenReturn(run);
        when(runtime.claim(eq("task"), eq("default"), eq("run"), anyString(), anyInt())).thenReturn(Optional.of(run));
        when(runtime.heartbeat(eq("task"), eq("default"), eq("run"), anyString(), anyLong(), anyInt())).thenReturn(true);
        when(links.advance(anyString(), anyString(), anyString(), any(), any(), any(), anyLong())).thenReturn(1);
    }

    private CountDownLatch waitForDependency(String dependency) {
        CountDownLatch latch = new CountDownLatch(1);
        when(runtime.waitForDependency(eq("task"), eq("default"), eq("run"), anyString(), eq(2L), eq(dependency)))
                .thenAnswer(call -> { latch.countDown(); return run; });
        return latch;
    }

    private static CreationMediaLink link(String stage, String jobId, String reviewId) {
        return new CreationMediaLink("run", "task", "default", "version", jobId, reviewId, stage, 2L, null);
    }

    private static RunView view(String status, long revision, int attempts) {
        return new RunView("run", "FULL", status, status, null, revision, attempts, null, null, null);
    }

    private static MediaProcessingJobResponse job(String jobId, String status) {
        return new MediaProcessingJobResponse(jobId, "version", "task", 10, "P720", "FLASH_PLUS_REVIEW", true,
                "v1", 10, 0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, status, status, 0, 0,
                null, null, null, null, null, null, List.of(), List.of(), "");
    }

    private static PreflightReviewResponse review(String reviewId, String status) {
        return new PreflightReviewResponse(reviewId, "task", "version", status, "DONE", 100, 0, false, 1, 3,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, 0L, "USD", null, null, LocalDateTime.now(),
                "COMPLETED".equals(status) ? LocalDateTime.now() : null, LocalDateTime.now(), LocalDateTime.now(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
