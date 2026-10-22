package com.link.linkagent.creator.creation.aivideo.provider;

import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 验证可配置异步协议适配：提交、查询、明确拒绝与结果不明。
 * 本地替身只证明协议解析与错误区分，不代表任何真实视频供应商已验收。
 */
class CompatibleVideoProviderTest {

    private static CreationVideoProperties settings() {
        var settings = new CreationVideoProperties();
        settings.setEnabled(true);
        settings.setBaseUrl("https://video.example.invalid/api");
        settings.setApiKey("secret-key");
        settings.setModel("test-video-1");
        settings.setSupportedDurationsSeconds(List.of(5, 10));
        settings.setUsdPerSecond(new BigDecimal("0.10"));
        return settings;
    }

    @Test
    void submitSendsPromptAndReturnsTaskIdentity() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), RestClient.create());
        server.expect(requestTo("https://video.example.invalid/api/video/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"model\":\"test-video-1\""),
                        org.hamcrest.Matchers.containsString("\"prompt\":\"海边日落\""),
                        org.hamcrest.Matchers.containsString("\"duration_seconds\":5"),
                        org.hamcrest.Matchers.containsString("\"size\":\"1280x720\""))))
                .andRespond(withSuccess("""
                        {"task_id":"task-9","request_id":"req-9"}
                        """, MediaType.APPLICATION_JSON));

        var ref = provider.submit(new VideoGenerationProvider.Submit("海边日落", 5, 1280, 720, 7));

        assertThat(ref.taskId()).isEqualTo("task-9");
        assertThat(ref.requestId()).isEqualTo("req-9");
        server.verify();
    }

    @Test
    void explicitClientRejectionIsNotTreatedAsUnknown() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), RestClient.create());
        server.expect(requestTo("https://video.example.invalid/api/video/generations"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> provider.submit(new VideoGenerationProvider.Submit("海边日落", 5, 1280, 720, 7)))
                .isInstanceOf(VideoGenerationProvider.VideoRejectedException.class);
    }

    @Test
    void serverErrorKeepsSubmissionUncertain() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), RestClient.create());
        server.expect(requestTo("https://video.example.invalid/api/video/generations"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.submit(new VideoGenerationProvider.Submit("海边日落", 5, 1280, 720, 7)))
                .isInstanceOf(VideoGenerationProvider.VideoUncertainException.class);
    }

    @Test
    void queryReadsNestedStatusAndUsageWithoutInventingMissingValues() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), RestClient.create());
        server.expect(requestTo("https://video.example.invalid/api/video/generations/task-9"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"output":{"task_status":"SUCCEEDED","video_url":"https://cdn.example.invalid/a.mp4",
                          "duration_seconds":5.0,"width":1280,"height":720},
                         "usage":{"duration":4}}
                        """, MediaType.APPLICATION_JSON));

        var result = provider.query("task-9");

        assertThat(result.status()).isEqualTo(VideoGenerationProvider.Status.SUCCEEDED);
        assertThat(result.videoUrl()).isEqualTo("https://cdn.example.invalid/a.mp4");
        assertThat(result.usageSeconds()).isEqualTo(4L);
        assertThat(result.durationMs()).isEqualTo(5000L);
        assertThat(result.width()).isEqualTo(1280);
        assertThat(result.height()).isEqualTo(720);
    }

    @Test
    void succeededWithoutVideoUrlIsAFailureRatherThanAReadyClip() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), RestClient.create());
        server.expect(requestTo("https://video.example.invalid/api/video/generations/task-9"))
                .andRespond(withSuccess("""
                        {"status":"succeeded"}
                        """, MediaType.APPLICATION_JSON));

        var result = provider.query("task-9");

        assertThat(result.status()).isEqualTo(VideoGenerationProvider.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo("RESULT_MISSING");
        assertThat(result.videoUrl()).isNull();
    }

    @Test
    void downloadUsesASeparateClientAndRejectsOversizedBodies() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new CompatibleVideoProvider(settings(), builder.build(), builder.build());
        server.expect(requestTo("https://cdn.example.invalid/a.mp4"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(new byte[]{0, 0, 0, 0, 'f', 't', 'y', 'p'}, MediaType.valueOf("video/mp4")));
        server.expect(requestTo("https://cdn.example.invalid/a.mp4"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(new byte[]{0, 0, 0, 0, 'f', 't', 'y', 'p'}, MediaType.valueOf("video/mp4")));

        byte[] bytes = provider.download("https://cdn.example.invalid/a.mp4", 1024);
        assertThat(bytes).hasSize(8);
        assertThatThrownBy(() -> provider.download("https://cdn.example.invalid/a.mp4", 4))
                .isInstanceOf(VideoGenerationProvider.VideoProviderException.class);
    }
}
