package com.link.linkagent.creator.media.preflight.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证真实联调返回的 transcripts/sentences 结构能稳定转为毫秒时间轴。
 */
class DashScopeAsrProviderTest {

    @Test
    void shouldReadUsageDurationFromCompletedTask() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DashScopeAsrProvider provider = new DashScopeAsrProvider(
                new CreatorMediaProperties.Preflight(),
                RestClient.create(),
                objectMapper
        );

        var result = provider.parseQueryResult(objectMapper.readTree("""
                {
                  "output": {
                    "task_status": "SUCCEEDED",
                    "result": {"transcription_url": "https://example.invalid/result.json"}
                  },
                  "usage": {"duration": 30}
                }
                """));

        assertThat(result.status()).isEqualTo(SpeechRecognitionProvider.Status.SUCCEEDED);
        assertThat(result.usageSeconds()).isEqualTo(30L);
        assertThat(result.transcriptionUrl()).isEqualTo("https://example.invalid/result.json");
    }

    @Test
    void shouldParseTimestampedTranscriptSegments() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DashScopeAsrProvider provider = new DashScopeAsrProvider(
                new CreatorMediaProperties.Preflight(),
                RestClient.create(),
                objectMapper
        );

        var result = provider.parseSegments(objectMapper.readTree("""
                {
                  "transcripts": [{
                    "language": "zh",
                    "sentences": [
                      {"begin_time": 6620, "end_time": 9140, "text": "就看一件。"},
                      {"begin_time": 10820, "end_time": 18800, "text": "不吭声的就往死里欺负。"}
                    ]
                  }]
                }
                """));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().startMs()).isEqualTo(6620L);
        assertThat(result.getFirst().endMs()).isEqualTo(9140L);
        assertThat(result.getFirst().text()).isEqualTo("就看一件。");
        assertThat(result.getFirst().language()).isEqualTo("zh");
    }
}
