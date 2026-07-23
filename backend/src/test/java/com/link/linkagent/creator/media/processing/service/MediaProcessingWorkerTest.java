package com.link.linkagent.creator.media.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0-2 FFmpeg 信号日志解析测试，不启动原生进程或访问对象存储。
 */
class MediaProcessingWorkerTest {

    @Test
    void shouldParseBlackSilenceFreezeAndVolumeSignals() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MediaProcessingWorker worker = new MediaProcessingWorker(
                new CreatorMediaProperties(),
                mock(MediaProcessingMapper.class),
                mock(MediaUploadMapper.class),
                mock(ObjectStorageService.class),
                objectMapper
        );
        try {
            String json = worker.parseSignalSummary("""
                    [blackdetect] black_start:1.5 black_end:3.0 black_duration:1.5
                    [silencedetect] silence_start: 5.0
                    [silencedetect] silence_end: 7.5 | silence_duration: 2.5
                    [freezedetect] freeze_start:10.0
                    [freezedetect] freeze_end:13.0 freeze_duration:3.0
                    [volumedetect] mean_volume: -24.5 dB
                    [volumedetect] max_volume: -1.2 dB
                    """, true);
            var root = objectMapper.readTree(json);

            assertThat(root.path("black").get(0).path("startSeconds").asDouble()).isEqualTo(1.5D);
            assertThat(root.path("silence").get(0).path("durationSeconds").asDouble()).isEqualTo(2.5D);
            assertThat(root.path("freeze").get(0).path("endSeconds").asDouble()).isEqualTo(13D);
            assertThat(root.path("meanVolumeDb").asDouble()).isEqualTo(-24.5D);
            assertThat(root.path("maxVolumeDb").asDouble()).isEqualTo(-1.2D);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void shouldReturnEmptyAudioSignalsWhenVideoHasNoAudio() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MediaProcessingWorker worker = new MediaProcessingWorker(
                new CreatorMediaProperties(),
                mock(MediaProcessingMapper.class),
                mock(MediaUploadMapper.class),
                mock(ObjectStorageService.class),
                objectMapper
        );
        try {
            var root = objectMapper.readTree(worker.parseSignalSummary("", false));

            assertThat(root.path("silence").size()).isZero();
            assertThat(root.path("meanVolumeDb").isNull()).isTrue();
            assertThat(root.path("maxVolumeDb").isNull()).isTrue();
        } finally {
            worker.shutdown();
        }
    }
}
