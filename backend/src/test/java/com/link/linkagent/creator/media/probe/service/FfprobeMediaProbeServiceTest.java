package com.link.linkagent.creator.media.probe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FFprobe JSON 解析边界测试，不启动原生进程或访问对象存储。
 */
class FfprobeMediaProbeServiceTest {

    @Test
    void shouldIgnoreAttachedPictureAndUseLongestVideoDuration() {
        FfprobeMediaProbeService service = service();

        var result = service.parseProbeJson("""
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,
                     "duration":"1.0","avg_frame_rate":"0/0","disposition":{"attached_pic":1}},
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                     "duration":"31.5","avg_frame_rate":"30000/1001","disposition":{"attached_pic":0}},
                    {"codec_type":"audio","codec_name":"aac","duration":"31.5"}
                  ],
                  "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"30.0",
                            "tags":{"major_brand":"isom"}}
                }
                """);

        assertThat(result.durationMs()).isEqualTo(31_500L);
        assertThat(result.width()).isEqualTo(1920);
        assertThat(result.videoCodec()).isEqualTo("h264");
        assertThat(result.hasAudio()).isTrue();
    }

    @Test
    void shouldRejectQuickTimeContainer() {
        MediaProbeException exception = catchThrowableOfType(
                () -> service().parseProbeJson("""
                        {
                          "streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                                      "duration":"10","avg_frame_rate":"30/1","disposition":{"attached_pic":0}}],
                          "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"10",
                                    "tags":{"major_brand":"qt  "}}
                        }
                        """),
                MediaProbeException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exception.getMessage()).contains("QuickTime");
    }

    @Test
    void shouldRejectAudioFileWithAttachedCoverOnly() {
        MediaProbeException exception = catchThrowableOfType(
                () -> service().parseProbeJson("""
                        {
                          "streams":[
                            {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,
                             "duration":"1","avg_frame_rate":"0/0","disposition":{"attached_pic":1}},
                            {"codec_type":"audio","codec_name":"aac","duration":"20"}
                          ],
                          "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"20",
                                    "tags":{"major_brand":"M4A"}}
                        }
                        """),
                MediaProbeException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exception.getMessage()).contains("缺少视频流");
    }

    private FfprobeMediaProbeService service() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        return new FfprobeMediaProbeService(properties, new ObjectMapper());
    }
}
