package com.jp.epubbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jp.epubbot.entity.BookmarkToken;
import com.jp.epubbot.entity.ReadingPosition;
import com.jp.epubbot.entity.UserBookmark;
import com.jp.epubbot.repository.BookmarkTokenRepository;
import com.jp.epubbot.repository.ReadingPositionRepository;
import com.jp.epubbot.repository.UserBookmarkRepository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private static final String DATA_DIR = "data";
    private static final String OLD_BOOKMARK_FILE = DATA_DIR + "/bookmarks.json";
    private static final String BACKUP_BOOKMARK_FILE = DATA_DIR + "/bookmarks.json.bak";

    private final ObjectMapper objectMapper;

    private final BookmarkTokenRepository tokenRepo;
    private final UserBookmarkRepository bookmarkRepo;
    private final ReadingPositionRepository positionRepo;

    // --- DTOs 用于前后端交互 (保持原有的 DTO 结构不变) ---
    @Data
    public static class BookmarkInfo {
        private String bookName;
        private String chapterTitle;
        private String url;

        public BookmarkInfo(String bookName, String chapterTitle, String url) {
            this.bookName = bookName;
            this.chapterTitle = chapterTitle;
            this.url = url;
        }
    }

    // --- 旧数据结构类，仅用于迁移 ---
    @Data
    public static class LegacyBookmarkData {
        private Map<String, BookmarkInfo> tokenMap;
        private Map<Long, List<BookmarkInfo>> userBookmarks;
        private Map<Long, Map<String, ReadingPosition>> userReadingPositions;
    }

    @PostConstruct
    @Transactional
    public void initAndMigrate() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File jsonFile = new File(OLD_BOOKMARK_FILE);
        if (jsonFile.exists()) {
            long dbCount = tokenRepo.count();
            if (dbCount == 0) {
                log.info("📢 检测到旧版 JSON 数据且数据库为空，开始迁移数据...");
                migrateFromJson(jsonFile);
            } else {
                log.info("ℹ️ 检测到 JSON 文件，但数据库已有数据，跳过迁移。");
            }
        }
    }

    private void migrateFromJson(File file) {
        try {
            LegacyBookmarkData data = objectMapper.readValue(file, LegacyBookmarkData.class);

            // 1. 迁移 Tokens
            if (data.getTokenMap() != null && !data.getTokenMap().isEmpty()) {
                List<BookmarkToken> tokens = new ArrayList<>();
                data.getTokenMap().forEach((tokenStr, info) -> {
                    BookmarkToken t = new BookmarkToken();
                    t.setToken(tokenStr);
                    t.setBookName(info.getBookName());
                    t.setChapterTitle(info.getChapterTitle());
                    t.setUrl(info.getUrl());
                    tokens.add(t);
                });
                tokenRepo.saveAll(tokens);
                log.info("✅ 迁移了 {} 个书籍链接 Token", tokens.size());
            }

            // 2. 迁移用户书签
            if (data.getUserBookmarks() != null && !data.getUserBookmarks().isEmpty()) {
                List<UserBookmark> bookmarks = new ArrayList<>();
                data.getUserBookmarks().forEach((userId, list) -> {
                    for (BookmarkInfo info : list) {
                        UserBookmark ub = new UserBookmark();
                        ub.setUserId(userId);
                        ub.setBookName(info.getBookName());
                        ub.setChapterTitle(info.getChapterTitle());
                        ub.setUrl(info.getUrl());
                        bookmarks.add(ub);
                    }
                });
                bookmarkRepo.saveAll(bookmarks);
                log.info("✅ 迁移了 {} 个用户书签", bookmarks.size());
            }

            // 3. 迁移阅读进度
            if (data.getUserReadingPositions() != null && !data.getUserReadingPositions().isEmpty()) {
                List<ReadingPosition> positions = new ArrayList<>();
                data.getUserReadingPositions().forEach((userId, map) -> {
                    map.values().forEach(oldPos -> {
                        // 注意：这里直接使用了 Entity 类，因为字段名和旧 JSON 结构大概率兼容
                        // 如果旧 JSON 里的 ReadingPosition 是内部类，这里 Jackson 反序列化时是兼容的
                        oldPos.setUserId(userId); // 确保 userId 被设置
                        positions.add(oldPos);
                    });
                });
                positionRepo.saveAll(positions);
                log.info("✅ 迁移了 {} 个阅读进度", positions.size());
            }

            // 重命名文件，避免下次重复检查
            if (file.renameTo(new File(BACKUP_BOOKMARK_FILE))) {
                log.info("🎉 迁移完成，旧数据文件已重命名为 .bak");
            }

        } catch (IOException e) {
            log.error("❌ 数据迁移失败", e);
        }
    }

    public String createBookmarkToken(String bookName, String chapterTitle, String url) {
        String tokenStr = "bm_" + UUID.randomUUID().toString().substring(0, 8);

        BookmarkToken token = new BookmarkToken();
        token.setToken(tokenStr);
        token.setBookName(bookName);
        token.setChapterTitle(chapterTitle);
        token.setUrl(url);

        tokenRepo.save(token);
        return tokenStr;
    }

    public BookmarkInfo getBookmarkByToken(String tokenStr) {
        return tokenRepo.findById(tokenStr)
                .map(t -> new BookmarkInfo(t.getBookName(), t.getChapterTitle(), t.getUrl()))
                .orElse(null);
    }

    public void saveBookmarkForUser(Long userId, BookmarkInfo info) {
        UserBookmark bookmark = new UserBookmark();
        bookmark.setUserId(userId);
        bookmark.setBookName(info.getBookName());
        bookmark.setChapterTitle(info.getChapterTitle());
        bookmark.setUrl(info.getUrl());
        bookmarkRepo.save(bookmark);
    }

    public List<BookmarkInfo> getUserBookmarks(Long userId) {
        return bookmarkRepo.findByUserId(userId).stream()
                .map(b -> new BookmarkInfo(b.getBookName(), b.getChapterTitle(), b.getUrl()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void clearBookmarks(Long userId) {
        bookmarkRepo.deleteByUserId(userId);
    }

    @Transactional
    public boolean deleteBookmarkForUser(Long userId, String url) {
        try {
            bookmarkRepo.deleteByUserIdAndUrl(userId, url);
            return true;
        } catch (Exception e) {
            log.error("删除书签失败", e);
            return false;
        }
    }

    // 对应 /list 命令
    public String findAllBooks() {
        List<BookmarkToken> books = tokenRepo.findAllFirstChapters();

        StringBuilder sb = new StringBuilder("🔖 **书籍列表:**\n\n");
        AtomicInteger index = new AtomicInteger(1);

        // 按书名排序
        books.stream()
                .sorted(Comparator.comparing(BookmarkToken::getBookName))
                .forEach(book ->
                        sb.append(index.getAndIncrement())
                                .append(". [").append(book.getBookName()).append("](").append(book.getUrl()).append(")\n")
                );

        if (books.isEmpty()) {
            return "暂无书籍数据。";
        }
        return sb.toString();
    }

    // 对应 MiniApp 的 getAllBooksStructured
    public List<Map<String, String>> getAllBooksStructuredWithSearch(String searchTerm) {
        List<BookmarkToken> tokens;

        if (searchTerm != null && !searchTerm.isBlank()) {
            tokens = tokenRepo.searchBooks(searchTerm);
        } else {
            tokens = tokenRepo.findAllFirstChapters();
        }

        List<Map<String, String>> books = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);

        // 简单去重 (如果 SQL 没过滤干净) 并转换为 Map
        Set<String> processedBooks = new TreeSet<>();

        for (BookmarkToken t : tokens) {
            if (processedBooks.contains(t.getBookName())) continue;

            Map<String, String> book = new HashMap<>();
            book.put("id", "book_" + index.getAndIncrement());
            book.put("name", t.getBookName());
            book.put("url", t.getUrl());
            book.put("firstPageTitle", t.getChapterTitle());
            books.add(book);
            processedBooks.add(t.getBookName());
        }

        return books;
    }

    // --- 阅读位置相关 ---

    public void saveReadingPosition(Long userId, ReadingPosition position) {
        if (userId == null || position == null || position.getBookName() == null) return;

        ReadingPosition existing = positionRepo.findByUserIdAndBookName(userId, position.getBookName())
                .orElse(new ReadingPosition());

        existing.setUserId(userId);
        existing.setBookName(position.getBookName());
        existing.setChapterTitle(position.getChapterTitle());
        existing.setUrl(position.getUrl());
        existing.setPosition(position.getPosition());

        // 校验进度
        double progress = position.getProgress() != null ? position.getProgress() : 0.0;
        existing.setProgress(Math.max(0.0, Math.min(100.0, progress)));

        existing.setTimestamp(System.currentTimeMillis());

        positionRepo.save(existing);
    }

    public ReadingPosition getReadingPosition(Long userId, String bookName) {
        return positionRepo.findByUserIdAndBookName(userId, bookName).orElse(null);
    }

    public List<ReadingPosition> getAllReadingPositions(Long userId) {
        return positionRepo.findByUserId(userId);
    }

    @Transactional
    public boolean deleteReadingPosition(Long userId, String bookName) {
        try {
            positionRepo.deleteByUserIdAndBookName(userId, bookName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void clearReadingPositions(Long userId) {
        positionRepo.deleteByUserId(userId);
    }

    public Map<String, Object> getReadingStats(Long userId) {
        Map<String, Object> stats = new HashMap<>();
        List<ReadingPosition> positions = positionRepo.findByUserIdOrderByTimestampDesc(userId);

        stats.put("totalBooks", positions.size());
        stats.put("recentlyRead", positions.stream().limit(5).collect(Collectors.toList()));

        if (!positions.isEmpty()) {
            double avg = positions.stream()
                    .mapToDouble(p -> p.getProgress() != null ? p.getProgress() : 0.0)
                    .average()
                    .orElse(0.0);
            stats.put("averageProgress", avg);
            stats.put("booksWithProgress", positions.size());
        } else {
            stats.put("averageProgress", 0);
            stats.put("booksWithProgress", 0);
        }
        return stats;
    }
}