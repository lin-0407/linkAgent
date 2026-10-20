package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从真实 Java 冻结和重写工程进入 Python 编码，不用两段各自成功的摘要计算代替接入。 */
@EnabledIfEnvironmentVariable(named="LINKAGENT_RENDERER_NATIVE_TESTS", matches="true")
class FixedProjectDecimalNativeTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(doubles={-0.0001, -0.000001, -0.1, -0.0})
    void decimalGainSurvivesFreezeNewAttemptAndActualPythonRendering(double gain) throws Exception {
        var json = new ObjectMapper();
        var settings = new FixedProjectRendererProperties(); settings.setEnabled(true);
        settings.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); settings.setPythonPath("python"); settings.setWorkRoot(root.toString());
        Path source = Files.createDirectories(root.resolve("decimal-run/source"));
        var wave = ByteBuffer.allocate(44 + 48000).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 48000).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(48000);
        for (int i=0;i<24000;i++) wave.putShort((short)(Math.sin(i * 2 * Math.PI * 440 / 24000) * 8000));
        Files.write(source.resolve("voice.wav"), wave.array());
        ObjectNode document = (ObjectNode) json.readTree("""
                {"schemaVersion":1,"runId":"decimal-run","attemptId":"source","storyboardId":"board","seed":7,
                 "video":{"width":1280,"height":720,"fps":24,"frameCount":48,"codec":"h264","audioSampleRate":48000,"fontId":"cjk_sans"},
                 "assets":[{"id":"voice","path":"voice.wav","mime":"audio/wav","sha256":""}],
                 "scenes":[{"id":"scene","startFrame":0,"endFrame":48,"layout":"TITLE_CARD","background":"#102030","title":"小数音量接入","body":[],
                            "voice":{"assetId":"voice","offsetMs":100,"gainDb":0}}],
                 "subtitles":[{"id":"cue","sceneId":"scene","startMs":100,"endMs":1100,"text":"测试音仅验证协议","audioAssetId":"voice"}]}
                """);
        ((ObjectNode)document.path("assets").get(0)).put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wave.array())));
        ((ObjectNode)document.path("scenes").get(0).path("voice")).put("gainDb", gain);
        String hash = CreationInputRules.renderInputHash(json, document); document.put("inputHash", hash);
        var input = source.resolve("input.json"); json.writeValue(input.toFile(), document);
        var files = new FixedProjectJobFiles(settings, json);
        var frozen = files.freeze("task", "default", new FixedProjectRenderService.Request(input,"decimal-run","source","board",hash));
        var run = mock(Run.class); when(run.runId()).thenReturn("decimal-run"); when(run.taskId()).thenReturn("task");
        when(run.ownerId()).thenReturn("default"); when(run.leaseGeneration()).thenReturn(1L);
        var prepared = files.prepare(frozen, run, () -> false);
        assertThat(CreationInputRules.renderContentHash(json, Files.readString(prepared.input())))
                .isEqualTo(CreationInputRules.renderContentHash(json, frozen.projectJson()));
        var result = new FixedProjectRenderService(settings,json).render(prepared, () -> false);
        assertThat(result.files()).hasSize(8); assertThat(result.durationMs()).isEqualTo(2000);
        assertThat(json.readTree(result.files().get("manifest.json").path().toFile()).path("media").path("fullDecodePassed").asBoolean()).isTrue();
    }
}
