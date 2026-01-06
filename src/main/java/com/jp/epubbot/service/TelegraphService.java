package com.jp.epubbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TelegraphService {

    @Value("${telegraph.author-name}")
    private String authorName;

    // 数据存储目录
    private static final String DATA_DIR = "data";
    private static final String TOKEN_FILE = DATA_DIR + "/telegraph_tokens.json";

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
        }
    }

    @PostConstruct
    public void init() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(TOKEN_FILE);
        if (file.exists()) {
            try {
                List<String> savedTokens = objectMapper.readValue(file, new TypeReference<List<String>>() {});
                for (String t : savedTokens) {
                    if (!tokenPool.contains(t)) {
                        tokenPool.add(t);
                    }
                }
                log.info("📂 已加载 {} 个 Telegraph Token。", tokenPool.size());
            } catch (IOException e) {
                log.error("加载 Token 文件失败", e);
            }
        }

        if (!tokenPool.isEmpty()) {
            currentAccessToken = tokenPool.get(0);
        }
    }

    private synchronized void saveTokens() {
        try {
            File file = new File(TOKEN_FILE);
            objectMapper.writeValue(file, tokenPool);
            log.info("💾 Token 已保存到文件。");
        } catch (IOException e) {
            log.error("保存 Token 失败", e);
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
            log.info("🆕 已创建新账户: {}...", newToken.substring(0, 8));
            saveTokens();

            return newToken;
        }

        return currentAccessToken;
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

    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([。，、；：？！])");
    private static final Pattern SPACE_BETWEEN_CHINESE = Pattern.compile("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])");

    public String cleanText(String text) {
        if (text == null) return "";
        text = SPACE_BEFORE_PUNCTUATION.matcher(text).replaceAll("$1");
        text = SPACE_BETWEEN_CHINESE.matcher(text).replaceAll("");
        return text;
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
                    String text = cleanText(((TextNode) child).text());
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

    private boolean isTokenInCooldown(String token) {
        Long unlockTime = tokenCooldowns.get(token);
        if (unlockTime == null) return false;
        if (System.currentTimeMillis() > unlockTime) {
            tokenCooldowns.remove(token);
            return false;
        }
        return true;
    }

    public PageResult createPage(String title, List<Map<String, Object>> contentNodes) {
        String url = "https://api.telegra.ph/createPage";
        int maxRetries = 10;
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
                    return new PageResult((String) result.get("path"), (String) result.get("url"), title, contentNodes, tokenToUse);
                } else {
                    if (handleFloodWait(tokenToUse, (String) response.get("error"))) continue;
                    else return null;
                }
            } catch (Exception e) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
        }
        return null;
    }

    public void editPage(String path, String title, List<Map<String, Object>> contentNodes, String requiredToken) {
        if (isTokenInCooldown(requiredToken)) return;
        String url = "https://api.telegra.ph/editPage";
        try {
            String contentJson = objectMapper.writeValueAsString(contentNodes);
            Map<String, Object> request = new HashMap<>();
            request.put("access_token", requiredToken);
            request.put("title", title);
            request.put("content", contentJson);
            request.put("path", path);
            request.put("return_content", false);
            restTemplate.postForObject(url, request, Map.class);
        } catch (Exception e) { log.error("EditPage failed", e); }
    }

    private boolean handleFloodWait(String token, String errorMsg) {
        if (errorMsg != null && errorMsg.startsWith("FLOOD_WAIT")) {
            Matcher matcher = FLOOD_WAIT_PATTERN.matcher(errorMsg);
            int waitSeconds = 5;
            if (matcher.find()) waitSeconds = Integer.parseInt(matcher.group(1));
            if (waitSeconds <= WAIT_THRESHOLD_SECONDS) {
                try { Thread.sleep((waitSeconds + 1) * 1000L); } catch (InterruptedException ignored) {}
            } else {
                tokenCooldowns.put(token, System.currentTimeMillis() + (waitSeconds + 2) * 1000L);
            }
            return true;
        }
        return false;
    }
}