package com.jp.epubbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jp.epubbot.entity.TelegraphAccount;
import com.jp.epubbot.repository.TelegraphAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TelegraphService {

    @Value("${telegraph.author-name:EpubReaderBot}")
    private String authorName;

    private final String initialToken;

    private static final int WAIT_THRESHOLD_SECONDS = 30;

    private final List<String> tokenPool = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> tokenCooldowns = new ConcurrentHashMap<>();
    private volatile String currentAccessToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern FLOOD_WAIT_PATTERN = Pattern.compile("FLOOD_WAIT_(\\d+)");

    private final TelegraphAccountRepository accountRepository;

    @Data
    public static class PageResult {
        private String path;
        private String url;
        private String title;
        private List<Map<String, Object>> content;
        private String usedToken;

        public PageResult(String path, String url, String title, List<Map<String, Object>> content, String usedToken) {
            this.path = path;
            this.url = url;
            this.title = title;
            this.content = content;
            this.usedToken = usedToken;
        }
    }

    public TelegraphService(@Value("${telegraph.access-token:}") String initialToken,
                            TelegraphAccountRepository accountRepository) {
        this.initialToken = initialToken;
        this.accountRepository = accountRepository;
    }

    @PostConstruct
    public void init() {
        List<TelegraphAccount> accounts = accountRepository.findAll();
        for (TelegraphAccount account : accounts) {
            if (!tokenPool.contains(account.getAccessToken())) {
                tokenPool.add(account.getAccessToken());
            }
        }
        log.info("📂 从数据库加载了 {} 个 Telegraph Token。", accounts.size());

        if (initialToken != null && !initialToken.isBlank() && !tokenPool.contains(initialToken)) {
            saveNewTokenToDb(initialToken);
            tokenPool.add(initialToken);
            log.info("📥 已导入配置文件中的初始 Token");
        }

        if (!tokenPool.isEmpty()) {
            currentAccessToken = tokenPool.get(0);
        }
    }

    private void saveNewTokenToDb(String token) {
        TelegraphAccount account = new TelegraphAccount();
        account.setAccessToken(token);
        account.setCreatedTime(System.currentTimeMillis());
        try {
            accountRepository.save(account);
        } catch (Exception e) {
            log.error("保存 Token 到数据库失败: {}", token, e);
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
            saveNewTokenToDb(newToken);
            currentAccessToken = newToken;
            log.info("🆕 已创建新账户: {}...", newToken.substring(0, 8));
            return newToken;
        }

        return currentAccessToken;
    }

    @SuppressWarnings("rawtypes")
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

    @SuppressWarnings("rawtypes")
    public String uploadImage(byte[] imageData, String contentType) {
        if (imageData == null || imageData.length == 0) return null;

        String uploadUrl = "https://telegra.ph/upload";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            String filename = "image." + (contentType.contains("png") ? "png" : "jpg");

            ByteArrayResource resource = new ByteArrayResource(imageData) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            body.add("file", resource);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            List response = restTemplate.postForObject(uploadUrl, requestEntity, List.class);

            if (response != null && !response.isEmpty()) {
                Map result = (Map) response.get(0);
                String src = (String) result.get("src");
                if (src != null) {
                    return "https://telegra.ph" + src;
                }
            }
        } catch (Exception e) {
            log.warn("图片上传失败: {}", e.getMessage());
        }
        return null;
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
            else if (tagName.equals("img")) {
                String src = element.attr("src");
                if (src.startsWith("http")) {
                    map.put("tag", "img");
                    map.put("attrs", Map.of("src", src));
                    return map;
                }
                return null;
            } else targetTag = forceInline ? null : "p";

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

    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([。，、；：？！])");
    private static final Pattern SPACE_BETWEEN_CHINESE = Pattern.compile("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])");

    public String cleanText(String text) {
        if (text == null) return "";
        text = SPACE_BEFORE_PUNCTUATION.matcher(text).replaceAll("$1");
        text = SPACE_BETWEEN_CHINESE.matcher(text).replaceAll("");
        return text;
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

    @SuppressWarnings("rawtypes")
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
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
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
        } catch (Exception e) {
            log.error("EditPage failed", e);
        }
    }

    private boolean handleFloodWait(String token, String errorMsg) {
        if (errorMsg != null && errorMsg.startsWith("FLOOD_WAIT")) {
            Matcher matcher = FLOOD_WAIT_PATTERN.matcher(errorMsg);
            int waitSeconds = 5;
            if (matcher.find()) waitSeconds = Integer.parseInt(matcher.group(1));
            if (waitSeconds <= WAIT_THRESHOLD_SECONDS) {
                try {
                    Thread.sleep((waitSeconds + 1) * 1000L);
                } catch (InterruptedException ignored) {
                }
            } else {
                tokenCooldowns.put(token, System.currentTimeMillis() + (waitSeconds + 2) * 1000L);
            }
            return true;
        }
        return false;
    }
}