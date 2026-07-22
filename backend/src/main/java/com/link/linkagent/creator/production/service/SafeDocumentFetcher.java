package com.link.linkagent.creator.production.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 官方资料抓取器。只允许 HTTPS、指定官方域名及公开地址，并限制重定向次数和响应大小。
 * 网页内容是外部不可信输入，返回给模型前不会当作系统指令执行。
 */
@Component
public class SafeDocumentFetcher {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 2;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public FetchedDocument fetch(String rawUrl, String officialDomain) {
        URI uri = validateUri(rawUrl, officialDomain);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "LinkAgent-ToolKnowledge/1.0")
                        .header("Accept", "text/html, text/plain, application/json;q=0.9")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    response.body().close();
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        throw new IllegalArgumentException("官方资料重定向缺少目标地址");
                    }
                    uri = validateUri(uri.resolve(location).toString(), officialDomain);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IllegalArgumentException("官方资料返回 HTTP " + status);
                }
                byte[] bytes;
                try (InputStream body = response.body()) {
                    bytes = body.readNBytes(MAX_BYTES + 1);
                }
                if (bytes.length > MAX_BYTES) {
                    throw new IllegalArgumentException("官方资料响应超过512KB限制");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");
                String lowerContentType = contentType.toLowerCase(Locale.ROOT);
                if (!(lowerContentType.contains("text/")
                        || lowerContentType.contains("html")
                        || lowerContentType.contains("json")
                        || lowerContentType.contains("xml"))) {
                    throw new IllegalArgumentException("官方资料不是可读取的文本内容");
                }
                return new FetchedDocument(uri.toString(), contentType,
                        new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalArgumentException("官方资料抓取失败", exception);
            }
        }
        throw new IllegalArgumentException("官方资料重定向次数超过限制");
    }

    private URI validateUri(String rawUrl, String officialDomain) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("官方资料链接格式不正确", exception);
        }
        String host = uri.getHost();
        String expectedDomain = officialDomain == null ? "" : officialDomain.toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equalsIgnoreCase(expectedDomain) || host.toLowerCase(Locale.ROOT).endsWith("." + expectedDomain))
                || isPrivateHost(host)) {
            throw new IllegalArgumentException("官方资料链接必须是指定官方域名下的公开 HTTPS 地址");
        }
        return uri;
    }

    private boolean isPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".local")) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
            }
        } catch (IOException exception) {
            return true;
        }
        return false;
    }

    public record FetchedDocument(String url, String contentType, String content) {
    }
}
