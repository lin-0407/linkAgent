package com.link.linkagent.creator.creation.voice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 协议字段由本地替身核对；没有真实密钥时不能据此宣称真实供应商已验收。 */
class DashScopeCosyVoiceProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requestsConfiguredModelVoiceAndReadsReportedCharacters() {
        var settings = new CreationVoiceProperties();
        settings.setEnabled(true);
        settings.validate();
        var preflight = new CreatorMediaProperties.Preflight();
        preflight.setDashScopeApiKey("test-key");
        preflight.setDashScopeBaseUrl("https://dashscope.test");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] audio = wav(240);
        server.expect(requestTo("https://dashscope.test/api/v1/services/aigc/multimodal-generation/generation"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("cosyvoice-v2"))
                .andExpect(jsonPath("$.input.text").value("今天先分类"))
                .andExpect(jsonPath("$.input.voice").value("longxiaochun_v2"))
                .andExpect(jsonPath("$.input.format").value("wav"))
                .andRespond(withSuccess("""
                        {"request_id":"req-1","output":{"audio":{"url":"https://dashscope.test/audio.wav"}},
                         "usage":{"characters":5}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dashscope.test/audio.wav"))
                .andRespond(withSuccess(audio, MediaType.parseMediaType("audio/wav")));

        var provider = new DashScopeCosyVoiceProvider(settings, preflight, json, builder.build());
        var result = provider.synthesize("今天先分类");

        assertThat(result.bytes()).isEqualTo(audio);
        assertThat(result.billedCharacters()).isEqualTo(5L);
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(provider.provider()).isEqualTo("dashscope");
        server.verify();
    }

    @Test
    void missingAudioIsAProviderFailureNotAnEmptySuccess() {
        var settings = new CreationVoiceProperties();
        settings.setEnabled(true);
        var preflight = new CreatorMediaProperties.Preflight();
        preflight.setDashScopeApiKey("test-key");
        preflight.setDashScopeBaseUrl("https://dashscope.test");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dashscope.test/api/v1/services/aigc/multimodal-generation/generation"))
                .andRespond(withSuccess("{\"request_id\":\"req-2\",\"output\":{}}", MediaType.APPLICATION_JSON));

        var provider = new DashScopeCosyVoiceProvider(settings, preflight, json, builder.build());
        assertThatThrownBy(() -> provider.synthesize("文稿")).isInstanceOf(DashScopeCosyVoiceProvider.SpeechSynthesisException.class)
                .hasMessageContaining("没有音频");
        server.verify();
    }

    @Test
    void missingKeyStopsBeforeAnyRequest() {
        var settings = new CreationVoiceProperties();
        settings.setEnabled(true);
        var preflight = new CreatorMediaProperties.Preflight();
        preflight.setDashScopeApiKey("");
        var provider = new DashScopeCosyVoiceProvider(settings, preflight, json, RestClient.create());
        assertThatThrownBy(() -> provider.synthesize("文稿")).hasMessageContaining("DASHSCOPE_API_KEY");
    }

    /** 44 字节固定头加静音采样，只用于协议替身，不作为真实语音证据。 */
    private static byte[] wav(int samples) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(samples * 2);
        for (int index = 0; index < samples; index++) buffer.putShort((short) 0);
        return buffer.array();
    }
}
