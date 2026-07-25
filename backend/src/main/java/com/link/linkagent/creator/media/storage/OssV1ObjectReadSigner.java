package com.link.linkagent.creator.media.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * 阿里云 OSS 原生 V1 GET 签名器。
 * 仅用于已实测不接受 S3 X-Amz 签名的 DashScope 文件转写回源请求。
 */
final class OssV1ObjectReadSigner {

    private OssV1ObjectReadSigner() {
    }

    static PresignedObjectRead sign(String endpoint,
                                    String bucketName,
                                    String objectKey,
                                    String accessKey,
                                    String secretKey,
                                    Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("OSS 签名有效期必须大于0");
        }
        URI endpointUri = URI.create(endpoint);
        String host = endpointUri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("OSS Provider Endpoint 无效");
        }
        // S3 兼容端点不能直接承载 OSS V1 鉴权，必须切回同地域的 OSS 原生域名。
        String nativeHost = host.startsWith("s3.oss-") ? host.substring(3) : host;
        Instant expiresAt = Instant.now().plus(duration);
        long expires = expiresAt.getEpochSecond();
        String resource = "/" + bucketName + "/" + objectKey;
        String stringToSign = "GET\n\n\n" + expires + "\n" + resource;
        String signature = hmacSha1(secretKey, stringToSign);
        String encodedPath = encodeObjectKey(objectKey);
        String url = endpointUri.getScheme() + "://" + bucketName + "." + nativeHost + "/" + encodedPath
                + "?OSSAccessKeyId=" + encodeQuery(accessKey)
                + "&Expires=" + expires
                + "&Signature=" + encodeQuery(signature);
        return new PresignedObjectRead(url, expiresAt);
    }

    private static String hmacSha1(String secretKey, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("OSS V1 签名计算失败", exception);
        }
    }

    private static String encodeObjectKey(String objectKey) {
        return java.util.Arrays.stream(objectKey.split("/", -1))
                .map(OssV1ObjectReadSigner::encodeQuery)
                .collect(Collectors.joining("/"));
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }
}
