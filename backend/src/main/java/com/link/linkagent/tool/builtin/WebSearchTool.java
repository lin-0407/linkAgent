package com.link.linkagent.tool.builtin;

import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 通过 wttr.in 免费 API 获取天气信息，无 API Key 需求。
 */
@Component
public class WebSearchTool implements Tool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search for weather information. Input: city name (e.g. Beijing). Uses wttr.in free API.";
    }

    @Override
    public String execute(String input) {
        try {
            String city = input.replace("天气", "")
                    .replace("temperature", "")
                    .replace("weather", "")
                    .trim();
            String url = "https://wttr.in/" + city + "?format=%C+%t+%h&lang=zh";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "Error: search failed - " + e.getMessage();
        }
    }
}
