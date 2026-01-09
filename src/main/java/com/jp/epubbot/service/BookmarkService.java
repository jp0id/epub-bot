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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BookmarkService {

    private static final String DATA_DIR = "data";
    private static final String BOOKMARK_FILE = DATA_DIR + "/bookmarks.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, BookmarkInfo> tokenMap = new ConcurrentHashMap<>();

    private final Map<Long, List<BookmarkInfo>> userBookmarks = new ConcurrentHashMap<>();

    private final Map<Long, Map<String, ReadingPosition>> userReadingPositions = new ConcurrentHashMap<>();

    @Data
    public static class BookmarkInfo {
        private String bookName;
        private String chapterTitle;
        private String url;
    }

    @Data
    public static class ReadingPosition {
        private String bookName;
        private String chapterTitle;
        private String url;
        private String position; // 可以是页码、位置标识等
        private Double progress; // 阅读进度百分比，0-100
        private Long timestamp; // 最后更新时间戳
    }

    @Data
    public static class BookmarkData {
        private Map<String, BookmarkInfo> tokenMap;
        private Map<Long, List<BookmarkInfo>> userBookmarks;
        private Map<Long, Map<String, ReadingPosition>> userReadingPositions;
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
                if (data.getUserReadingPositions() != null) {
                    this.userReadingPositions.putAll(data.getUserReadingPositions());
                }
                log.info("📂 已加载书签数据: {} 个用户, {} 个活跃链接, {} 个阅读位置记录。", userBookmarks.size(), tokenMap.size(), userReadingPositions.size());
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
            data.setUserReadingPositions(this.userReadingPositions);

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

    public boolean deleteBookmarkForUser(Long userId, String url) {
        List<BookmarkInfo> bookmarks = userBookmarks.get(userId);
        if (bookmarks == null) {
            return false;
        }
        boolean removed = bookmarks.removeIf(info -> url.equals(info.getUrl()));
        if (removed) {
            saveData();
        }
        return removed;
    }

    public String findAllBooks() {
        Map<String, String> booksInfo = new TreeMap<>();
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
        AtomicInteger index = new AtomicInteger(1);
        booksInfo.forEach((bookName, url) ->
                sb.append(index.getAndIncrement())
                        .append(". [").append(bookName).append("](").append(url).append(")\n")
        );
        return sb.toString();
    }

    public List<Map<String, String>> getAllBooksStructured() {
        return getAllBooksStructuredWithSearch(null);
    }

    public List<Map<String, String>> getAllBooksStructuredWithSearch(String searchTerm) {
        Map<String, String> booksInfo = new TreeMap<>();
        this.tokenMap.values().stream()
                .filter(info -> {
                    String title = info.getChapterTitle();
                    String name = info.getBookName();
                    return title != null && name != null
                           && title.contains(name)
                           && title.contains("(1)");
                })
                .filter(info -> {
                    if (searchTerm == null || searchTerm.isBlank()) {
                        return true;
                    }
                    String name = info.getBookName();
                    return name.toLowerCase().contains(searchTerm.toLowerCase());
                })
                .forEach(info -> booksInfo.put(info.getBookName(), info.getUrl()));

        List<Map<String, String>> books = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);
        booksInfo.forEach((bookName, url) -> {
            Map<String, String> book = new HashMap<>();
            book.put("id", "book_" + index.getAndIncrement());
            book.put("name", bookName);
            book.put("url", url);
            book.put("firstPageTitle", bookName + " (1)");
            books.add(book);
        });

        return books;
    }

    /**
     * 保存或更新阅读位置
     */
    public void saveReadingPosition(Long userId, ReadingPosition position) {
        if (userId == null || position == null || position.getBookName() == null) {
            log.warn("保存阅读位置失败: 参数不能为空");
            return;
        }

        // 确保时间戳
        if (position.getTimestamp() == null) {
            position.setTimestamp(System.currentTimeMillis());
        }

        // 确保进度在0-100范围内
        if (position.getProgress() != null) {
            if (position.getProgress() < 0) position.setProgress(0.0);
            if (position.getProgress() > 100) position.setProgress(100.0);
        }

        // 使用书籍名称作为key，如果书籍名称可能重复，可以考虑使用URL或其他唯一标识
        String bookKey = position.getBookName();
        userReadingPositions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                           .put(bookKey, position);
        saveData();
        log.info("已保存用户 {} 的书籍 {} 阅读位置", userId, bookKey);
    }

    /**
     * 获取用户的特定书籍阅读位置
     */
    public ReadingPosition getReadingPosition(Long userId, String bookName) {
        if (userId == null || bookName == null) {
            return null;
        }
        Map<String, ReadingPosition> userPositions = userReadingPositions.get(userId);
        if (userPositions == null) {
            return null;
        }
        return userPositions.get(bookName);
    }

    /**
     * 获取用户的所有阅读位置
     */
    public List<ReadingPosition> getAllReadingPositions(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Map<String, ReadingPosition> userPositions = userReadingPositions.get(userId);
        if (userPositions == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(userPositions.values());
    }

    /**
     * 删除用户的特定书籍阅读位置
     */
    public boolean deleteReadingPosition(Long userId, String bookName) {
        if (userId == null || bookName == null) {
            return false;
        }
        Map<String, ReadingPosition> userPositions = userReadingPositions.get(userId);
        if (userPositions == null) {
            return false;
        }
        boolean removed = userPositions.remove(bookName) != null;
        if (removed) {
            // 如果用户没有其他阅读位置，移除整个用户条目
            if (userPositions.isEmpty()) {
                userReadingPositions.remove(userId);
            }
            saveData();
            log.info("已删除用户 {} 的书籍 {} 阅读位置", userId, bookName);
        }
        return removed;
    }

    /**
     * 清除用户的所有阅读位置
     */
    public void clearReadingPositions(Long userId) {
        if (userId == null) {
            return;
        }
        Map<String, ReadingPosition> removed = userReadingPositions.remove(userId);
        if (removed != null && !removed.isEmpty()) {
            saveData();
            log.info("已清除用户 {} 的所有阅读位置，共 {} 条记录", userId, removed.size());
        }
    }

    /**
     * 获取用户的阅读进度统计
     */
    public Map<String, Object> getReadingStats(Long userId) {
        Map<String, Object> stats = new HashMap<>();
        if (userId == null) {
            return stats;
        }

        Map<String, ReadingPosition> userPositions = userReadingPositions.get(userId);
        if (userPositions == null || userPositions.isEmpty()) {
            stats.put("totalBooks", 0);
            stats.put("recentlyRead", Collections.emptyList());
            return stats;
        }

        int totalBooks = userPositions.size();
        stats.put("totalBooks", totalBooks);

        // 获取最近阅读的书籍（按时间戳排序）
        List<ReadingPosition> recentPositions = new ArrayList<>(userPositions.values());
        recentPositions.sort((a, b) -> {
            Long timeA = a.getTimestamp() != null ? a.getTimestamp() : 0L;
            Long timeB = b.getTimestamp() != null ? b.getTimestamp() : 0L;
            return timeB.compareTo(timeA); // 降序排序
        });

        // 只取最近5本
        int limit = Math.min(5, recentPositions.size());
        stats.put("recentlyRead", recentPositions.subList(0, limit));

        // 计算平均进度
        double totalProgress = 0;
        int countWithProgress = 0;
        for (ReadingPosition pos : userPositions.values()) {
            if (pos.getProgress() != null) {
                totalProgress += pos.getProgress();
                countWithProgress++;
            }
        }

        if (countWithProgress > 0) {
            stats.put("averageProgress", totalProgress / countWithProgress);
            stats.put("booksWithProgress", countWithProgress);
        }

        return stats;
    }
}