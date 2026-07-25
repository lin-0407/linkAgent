package com.link.linkagent.creator.media.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ASR 使用 OSS 原生签名参数，避免回退成 DashScope 已拒绝的 X-Amz 签名。
 */
class OssV1ObjectReadSignerTest {

    @Test
    void shouldCreateNativeOssV1ReadUrlForDashScope() {
        PresignedObjectRead read = OssV1ObjectReadSigner.sign(
                "https://s3.oss-cn-hongkong.aliyuncs.com",
                "private-bucket",
                "users/default/audio sample.mp3",
                "access-id",
                "secret-value",
                Duration.ofMinutes(5)
        );

        URI uri = URI.create(read.url());
        Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
                .map(item -> item.split("=", 2))
                .collect(Collectors.toMap(
                        item -> item[0],
                        item -> URLDecoder.decode(item[1], StandardCharsets.UTF_8)
                ));

        assertThat(uri.getHost()).isEqualTo("private-bucket.oss-cn-hongkong.aliyuncs.com");
        assertThat(uri.getRawPath()).isEqualTo("/users/default/audio%20sample.mp3");
        assertThat(query).containsKeys("OSSAccessKeyId", "Expires", "Signature");
        assertThat(query).doesNotContainKeys("X-Amz-Algorithm", "X-Amz-Credential");
    }
}
