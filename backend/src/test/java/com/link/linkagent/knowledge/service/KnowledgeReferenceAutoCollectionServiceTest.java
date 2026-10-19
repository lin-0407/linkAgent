package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeReferenceAutoCollectionServiceTest {
    @TempDir Path root;
    KnowledgeReferenceAutoCollectionService service;

    @AfterEach void close() { if (service != null) service.close(); }

    @Test
    void shouldRunPythonAsynchronouslyAndReturnActualReport() throws Exception {
        prepare();
        assertThat(service.status().status()).isEqualTo("IDLE");
        var started = service.start(new ReferenceAutoCollectionRequest(61.5, 10000, 10000, List.of(4)), 18081, "");
        assertThat(started.status()).isEqualTo("RUNNING");
        awaitFinished();
        var result = service.status();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.report().path("imported").asInt()).isEqualTo(10000);
        assertThat(result.report().path("receivedApi").asText()).isEqualTo("http://127.0.0.1:18081");
        assertThat(result.settings().minIndex()).isEqualTo(61.5);
        assertThat(result.settings().maxImports()).isEqualTo(10000);
        assertThat(result.settings().rids()).containsExactly(4);
        assertThat(result.finishedAt()).isNotNull();
    }

    @Test
    void shouldRejectDuplicateStartAndStopTheOwnedProcess() throws Exception {
        prepare();
        var request = new ReferenceAutoCollectionRequest(99.0, 1, 1, List.of());
        service.start(request, 18081, "");
        assertThatThrownBy(() -> service.start(request, 18081, ""))
                .hasMessageContaining("409");
        service.stop();
        awaitFinished();
        assertThat(service.status().status()).isEqualTo("CANCELLED");
        assertThat(service.status().message()).contains("已经入库的案例会保留");
    }

    private void prepare() throws Exception {
        Path scripts = Files.createDirectories(root.resolve("scripts"));
        Files.writeString(scripts.resolve("bilibili_reference_auto.example.json"),
                "{\"rids\":[],\"min_index\":60,\"max_imports\":10,\"max_checks\":100}");
        // 控制脚本只验证进程、参数、报告与停止，不访问真实B站或业务数据库。
        Files.writeString(scripts.resolve("bilibili_reference_auto.py"), """
                import argparse,json,time
                from pathlib import Path
                p=argparse.ArgumentParser();p.add_argument('--config');p.add_argument('--report');a=p.parse_args()
                c=json.loads(Path(a.config).read_text(encoding='utf-8'))
                if c['min_index']==99:time.sleep(30)
                Path(a.report).write_text(json.dumps({'imported':c['max_imports'],'receivedApi':c['api_base']}),encoding='utf-8')
                """);
        var fetch = mock(KnowledgeReferenceFetchService.class);
        when(fetch.resolveProjectRoot()).thenReturn(root);
        service = new KnowledgeReferenceAutoCollectionService(fetch, new ObjectMapper(), "python", "");
    }

    private void awaitFinished() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (List.of("RUNNING", "STOPPING").contains(service.status().status()) && System.nanoTime() < deadline) Thread.sleep(30);
        assertThat(service.status().status()).isNotIn("RUNNING", "STOPPING");
    }
}
