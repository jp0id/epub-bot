package com.jp.epubbot.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * @Author: J.P
 * @Date: 2026/1/26 13:24
 */

@Service
@ConfigurationProperties(prefix = "cloudflare")
@Data
@Slf4j
public class CloudflareService {
    private String apiToken;

    private Map<String, String> zones;

    private static final String CF_API_URL_TEMPLATE = "https://api.cloudflare.com/client/v4/zones/%s/purge_cache";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 根据 URL 自动识别域名，找到对应的 Zone ID 进行清除
     */
    public void purgeCache(String fileUrl) {
        try {
            String host = URI.create(fileUrl).getHost();
            String zoneId = zones.get(host);
            if (zoneId == null) {
                log.error("未找到域名 [{}] 对应的 Cloudflare Zone ID，跳过清除缓存。", host);
                return;
            }
            sendPurgeRequest(zoneId, fileUrl);
        } catch (Exception e) {
            log.error("purgeCache error: [{}]", e.toString());
        }
    }

    private void sendPurgeRequest(String zoneId, String fileUrl) {
        try {
            String url = String.format(CF_API_URL_TEMPLATE, zoneId);
            String jsonBody = String.format("{\"files\": [\"%s\"]}", fileUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("成功清除缓存 [{}]: {}", zoneId, fileUrl);
            } else {
                log.error("清除缓存失败 [{}]: {}", zoneId, response.body());
            }
        } catch (Exception e) {
            log.error("sendPurgeRequest error: [{}]", e.toString());
        }
    }
}
