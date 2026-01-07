package com.jp.epubbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BookmarkService {

    private static final String DATA_DIR = "data";
    private static final String BOOKMARK_FILE = DATA_DIR + "/bookmarks.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, BookmarkInfo> tokenMap = new ConcurrentHashMap<>();

    private final Map<Long, List<BookmarkInfo>> userBookmarks = new ConcurrentHashMap<>();

    @Data
    public static class BookmarkInfo {
        private String bookName;
        private String chapterTitle;
        private String url;
    }

    @Data
    public static class BookmarkData {
        private Map<String, BookmarkInfo> tokenMap;
        private Map<Long, List<BookmarkInfo>> userBookmarks;
    }

    @PostConstruct
    public void init() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(BOOKMARK_FILE);
        if (file.exists()) {
            try {
                BookmarkData data = objectMapper.readValue(file, BookmarkData.class);
                if (data.getTokenMap() != null) {
                    this.tokenMap.putAll(data.getTokenMap());
                }
                if (data.getUserBookmarks() != null) {
                    this.userBookmarks.putAll(data.getUserBookmarks());
                }
                log.info("📂 已加载书签数据: {} 个用户, {} 个活跃链接。", userBookmarks.size(), tokenMap.size());
            } catch (IOException e) {
                log.error("加载书签文件失败", e);
            }
        }
    }

    private synchronized void saveData() {
        try {
            BookmarkData data = new BookmarkData();
            data.setTokenMap(this.tokenMap);
            data.setUserBookmarks(this.userBookmarks);

            File file = new File(BOOKMARK_FILE);
            objectMapper.writeValue(file, data);
        } catch (IOException e) {
            log.error("保存书签数据失败", e);
        }
    }

    public String createBookmarkToken(String bookName, String chapterTitle, String url) {
        String token = "bm_" + UUID.randomUUID().toString().substring(0, 8);
        BookmarkInfo info = new BookmarkInfo();
        info.setBookName(bookName);
        info.setChapterTitle(chapterTitle);
        info.setUrl(url);

        tokenMap.put(token, info);
        saveData();
        return token;
    }

    public BookmarkInfo getBookmarkByToken(String token) {
        return tokenMap.get(token);
    }

    public void saveBookmarkForUser(Long userId, BookmarkInfo info) {
        userBookmarks.computeIfAbsent(userId, k -> new ArrayList<>()).add(info);
        saveData();
    }

    public List<BookmarkInfo> getUserBookmarks(Long userId) {
        return userBookmarks.getOrDefault(userId, Collections.emptyList());
    }

    public void clearBookmarks(Long userId) {
        userBookmarks.remove(userId);
        saveData();
    }

    public String findAllBooks() {
        Map<String, String> booksInfo = new HashMap<>();

        this.tokenMap.values().stream()
                .filter(info -> {
                    String title = info.getChapterTitle();
                    String name = info.getBookName();
                    return title != null && name != null
                           && title.contains(name)
                           && title.contains("(1)");
                })
                .forEach(info -> booksInfo.put(info.getBookName(), info.getUrl()));

        StringBuilder sb = new StringBuilder("🔖 **书籍列表:**\n\n");
        booksInfo.forEach((bookName, url) ->
                sb.append("[").append(bookName).append("](").append(url).append(")\n")
        );
        return sb.toString();
    }
}