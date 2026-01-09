package com.jp.epubbot.service;

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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private static final String DATA_DIR = "data";

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

    @PostConstruct
    @Transactional
    public void initAndMigrate() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
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