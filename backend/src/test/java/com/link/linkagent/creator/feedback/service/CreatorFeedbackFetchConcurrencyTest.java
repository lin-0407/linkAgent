package com.link.linkagent.creator.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackFetchRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.support.TestTransactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CreatorFeedbackFetchConcurrencyTest {
    @TempDir Path directory;

    @Test
    void shouldImportEachConcurrentSameBvRequestsOwnFeedbackAndReturnItsFiles() throws Exception {
        Path scripts = Files.createDirectories(directory.resolve("scripts"));
        Files.writeString(scripts.resolve("bilibili_feedback_fetcher.py"), """
                import json, sys, time
                from pathlib import Path
                args = sys.argv
                root = Path(__file__).resolve().parent.parent
                out = Path(args[args.index('--output-dir') + 1])
                marker = 'A' if args[args.index('--max-comments') + 1] == '1' else 'B'
                def wait_for(path):
                    until = time.monotonic() + 10
                    while not path.exists():
                        if time.monotonic() > until: raise RuntimeError('采集并发测试等待超时')
                        time.sleep(.01)
                if marker == 'B': wait_for(root / 'A.written')
                payload = {'comments': {'rootComments': [{'message': marker}]}}
                (out / (args[1] + '_feedback.json')).write_text(json.dumps(payload), encoding='utf-8')
                (out / (args[1] + '_feedback.txt')).write_text(marker, encoding='utf-8')
                (root / (marker + '.written')).write_text(str(out), encoding='utf-8')
                if marker == 'A':
                    wait_for(root / 'B.written')
                    time.sleep(.2)
                """);
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorBilibiliMapper bilibiliMapper = mock(CreatorBilibiliMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llm = mock(LLMService.class);
        var inserted = new ConcurrentLinkedQueue<CreatorFeedbackItemRecord>();
        doAnswer(call -> { inserted.add(call.getArgument(0)); return 1; })
                .when(feedbackMapper).insertItem(any());
        for (String marker : new String[]{"A", "B"}) {
            CreatorTaskRecord task = new CreatorTaskRecord();
            task.setTaskId("task-" + marker); task.setUserId("default");
            when(taskMapper.findTaskByTaskId(task.getTaskId())).thenReturn(Optional.of(task));
            TaskVideoBindingRecord binding = new TaskVideoBindingRecord();
            binding.setTaskId(task.getTaskId()); binding.setBvid("BV1234567890"); binding.setBindingStatus("BOUND");
            when(bilibiliMapper.findBindingByTaskId(task.getTaskId())).thenReturn(Optional.of(binding));
        }
        CreatorFeedbackService service = new CreatorFeedbackService(taskMapper, bilibiliMapper, feedbackMapper,
                mock(CreatorReviewInvalidationMapper.class), llm, new ObjectMapper(), TestTransactions.directTemplate(),
                mock(CreatorFeedbackEvidenceRetrievalService.class), mock(PromptService.class),
                mock(CreatorMediaWorkflowGateService.class), mock(CreatorPublishedVideoContextService.class)) {
            @Override Path resolveProjectRoot() { return directory; }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.fetchFeedback("task-A",
                    new CreatorFeedbackFetchRequest("BV1234567890", 1, 0, 0, "both")));
            var second = executor.submit(() -> service.fetchFeedback("task-B",
                    new CreatorFeedbackFetchRequest("BV1234567890", 2, 0, 0, "both")));
            var responseA = first.get(20, TimeUnit.SECONDS);
            var responseB = second.get(20, TimeUnit.SECONDS);
            assertThat(inserted).anySatisfy(item -> {
                assertThat(item.getTaskId()).isEqualTo("task-A");
                assertThat(item.getContent()).isEqualTo("A");
            }).anySatisfy(item -> {
                assertThat(item.getTaskId()).isEqualTo("task-B");
                assertThat(item.getContent()).isEqualTo("B");
            }).hasSize(2);
            assertThat(responseA.outputDirectory()).isNotEqualTo(responseB.outputDirectory());
            assertThat(responseA.outputFiles()).hasSize(2).allSatisfy(file ->
                    assertThat(Path.of(file).getParent()).isEqualTo(Path.of(responseA.outputDirectory())));
            assertThat(responseB.outputFiles()).hasSize(2).allSatisfy(file ->
                    assertThat(Path.of(file).getParent()).isEqualTo(Path.of(responseB.outputDirectory())));
            // 单字样本在规则阶段过滤，回归不能因采集测试而发起任何模型请求。
            verifyNoInteractions(llm);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }
}
