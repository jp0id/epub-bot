package com.jp.epubbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TelegraphService {

    @Value("${telegraph.author-name}")
    private String authorName;

    private static final int WAIT_THRESHOLD_SECONDS = 30;

    private final List<String> tokenPool = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> tokenCooldowns = new ConcurrentHashMap<>();
    private volatile String currentAccessToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern FLOOD_WAIT_PATTERN = Pattern.compile("FLOOD_WAIT_(\\d+)");

    @Data
    @AllArgsConstructor
    public static class PageResult {
        private String path;
        private String url;
        private String title;
        private List<Map<String, Object>> content;
        private String usedToken;
    }

    public TelegraphService(@Value("${telegraph.access-token:}") String initialToken) {
        if (initialToken != null && !initialToken.isEmpty()) {
            tokenPool.add(initialToken);
            currentAccessToken = initialToken;
        }
    }

    private synchronized String getValidToken() {
        if (currentAccessToken != null && !isTokenInCooldown(currentAccessToken)) {
            return currentAccessToken;
        }

        for (String token : tokenPool) {
            if (!isTokenInCooldown(token)) {
                currentAccessToken = token;
                log.info("🔄 切换到现存 Token: {}...", token.substring(0, 8));
                return token;
            }
        }

        String newToken = createNewAccount();
        if (newToken != null) {
            tokenPool.add(newToken);
            currentAccessToken = newToken;
            log.info("🆕 池中无可用 Token，已创建新账户: {}...", newToken.substring(0, 8));
            return newToken;
        }

        return currentAccessToken;
    }

    private boolean isTokenInCooldown(String token) {
        Long unlockTime = tokenCooldowns.get(token);
        if (unlockTime == null) return false;
        if (System.currentTimeMillis() > unlockTime) {
            tokenCooldowns.remove(token);
            return false;
        }
        return true;
    }

    private String createNewAccount() {
        String url = "https://api.telegra.ph/createAccount?short_name=reader&author_name=" + authorName;
        try {
            Map response = restTemplate.getForObject(url, Map.class);
            if (response != null && (Boolean) response.get("ok")) {
                Map result = (Map) response.get("result");
                return (String) result.get("access_token");
            }
        } catch (Exception e) {
            log.error("创建新 Telegraph 账户失败", e);
        }
        return null;
    }

    public PageResult createPage(String title, List<Map<String, Object>> contentNodes) {
        String url = "https://api.telegra.ph/createPage";
        int maxRetries = 10; // 增加重试次数，因为包含了短等待的情况

        for (int i = 0; i < maxRetries; i++) {
            String tokenToUse = getValidToken();

            try {
                String contentJson = objectMapper.writeValueAsString(contentNodes);
                Map<String, Object> request = new HashMap<>();
                request.put("access_token", tokenToUse);
                request.put("title", title);
                request.put("content", contentJson);
                request.put("return_content", false);

                Map response = restTemplate.postForObject(url, request, Map.class);

                if (response != null && (Boolean) response.get("ok")) {
                    Map result = (Map) response.get("result");
                    return new PageResult(
                            (String) result.get("path"),
                            (String) result.get("url"),
                            title,
                            contentNodes,
                            tokenToUse
                    );
                } else {
                    String errorMsg = (String) response.get("error");
                    if (handleFloodWait(tokenToUse, errorMsg)) {
                        continue;
                    } else {
                        log.error("不可恢复的 API 错误 (Token: {}): {}", tokenToUse.substring(0, 8), errorMsg);
                        return null;
                    }
                }
            } catch (Exception e) {
                log.error("CreatePage 请求异常", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    /**
     * 编辑页面 (必须用原 Token，所以只能等待，不能切换)
     */
    public void editPage(String path, String title, List<Map<String, Object>> contentNodes, String requiredToken) {
        if (isTokenInCooldown(requiredToken)) {
            log.warn("Token {} 冷却中，跳过编辑 {}", requiredToken.substring(0, 8), path);
            return;
        }

        String url = "https://api.telegra.ph/editPage";
        for (int i = 0; i < 3; i++) {
            try {
                String contentJson = objectMapper.writeValueAsString(contentNodes);
                Map<String, Object> request = new HashMap<>();
                request.put("access_token", requiredToken);
                request.put("title", title);
                request.put("content", contentJson);
                request.put("path", path);
                request.put("return_content", false);

                Map response = restTemplate.postForObject(url, request, Map.class);
                if (response != null && (Boolean) response.get("ok")) {
                    return; // 成功
                } else {
                    String errorMsg = (String) (response != null ? response.get("error") : "Unknown");
                    if (errorMsg != null && errorMsg.startsWith("FLOOD_WAIT")) {
                        Matcher matcher = FLOOD_WAIT_PATTERN.matcher(errorMsg);
                        if (matcher.find()) {
                            int waitSeconds = Integer.parseInt(matcher.group(1));
                            if (waitSeconds <= WAIT_THRESHOLD_SECONDS) {
                                log.info("编辑限流 {}s，等待中...", waitSeconds);
                                Thread.sleep((waitSeconds + 1) * 1000L);
                                continue;
                            } else {
                                log.warn("编辑限流 {}s (超过阈值)，放弃编辑。", waitSeconds);
                                tokenCooldowns.put(requiredToken, System.currentTimeMillis() + (waitSeconds + 2) * 1000L);
                                return;
                            }
                        }
                    }
                    log.warn("编辑失败: {}", errorMsg);
                    return;
                }
            } catch (Exception e) {
                log.error("EditPage 异常", e);
                return;
            }
        }
    }

    private boolean handleFloodWait(String token, String errorMsg) {
        if (errorMsg == null) return false;

        if (errorMsg.startsWith("FLOOD_WAIT")) {
            Matcher matcher = FLOOD_WAIT_PATTERN.matcher(errorMsg);
            int waitSeconds = 5;
            if (matcher.find()) {
                waitSeconds = Integer.parseInt(matcher.group(1));
            }

            if (waitSeconds <= WAIT_THRESHOLD_SECONDS) {
                log.info("⏳ 触发限流 {}s (<= {}s)，原地休眠等待...", waitSeconds, WAIT_THRESHOLD_SECONDS);
                try {
                    Thread.sleep((waitSeconds + 1) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                log.info("🚫 触发限流 {}s (> {}s)，标记冷却并切换账号...", waitSeconds, WAIT_THRESHOLD_SECONDS);
                long cooldownUntil = System.currentTimeMillis() + (waitSeconds + 2) * 1000L;
                tokenCooldowns.put(token, cooldownUntil);
            }
            return true;
        }
        return false;
    }

    private static final Set<String> BLOCK_TAGS = Set.of("p", "h3", "h4", "blockquote", "aside", "figure", "ul", "ol", "hr");
    private static final Set<String> INLINE_TAGS = Set.of("b", "strong", "i", "em", "u", "s", "a", "code", "br");

    public Map<String, Object> convertNode(Node node, boolean forceInline) {
        if (node instanceof TextNode) return null;
        if (node instanceof Element element) {
            String tagName = element.tagName().toLowerCase();
            Map<String, Object> map = new HashMap<>();
            List<Object> children = new ArrayList<>();
            String targetTag;
            if (BLOCK_TAGS.contains(tagName)) targetTag = forceInline ? "span" : tagName;
            else if (INLINE_TAGS.contains(tagName)) targetTag = tagName;
            else if (tagName.equals("h1") || tagName.equals("h2")) targetTag = forceInline ? "b" : "h3";
            else if (tagName.equals("img")) return null;
            else targetTag = forceInline ? null : "p";

            for (Node child : element.childNodes()) {
                if (child instanceof TextNode) {
                    String text = ((TextNode) child).text();
                    if (!text.isEmpty()) children.add(text);
                } else if (child instanceof Element) {
                    boolean childForceInline = forceInline || "p".equals(targetTag) || INLINE_TAGS.contains(targetTag);
                    Map<String, Object> childMap = convertNode(child, childForceInline);
                    if (childMap != null) children.add(childMap);
                }
            }
            if (targetTag == null) targetTag = "p";
            if (!children.isEmpty() || tagName.equals("br") || tagName.equals("hr")) {
                map.put("tag", targetTag);
                if (!children.isEmpty()) map.put("children", children);
                return map;
            }
        }
        return null;
    }
}