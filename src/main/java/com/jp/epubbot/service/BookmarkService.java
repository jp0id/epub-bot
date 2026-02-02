package com.jp.epubbot.service;

import com.jp.epubbot.entity.BookmarkToken;
import com.jp.epubbot.entity.UserBookmark;
import com.jp.epubbot.repository.BookmarkTokenRepository;
import com.jp.epubbot.repository.UserBookmarkRepository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookmarkService {

    @Value("${telegram.bot.admins:}")
    private String adminList;

    private static final String DATA_DIR = "data";

    private final R2StorageService r2StorageService;
    private final BookmarkTokenRepository tokenRepo;
    private final UserBookmarkRepository bookmarkRepo;
    private final LocalBookService localBookService;
    private final CloudflareService cloudflareService;

    private List<String> admins;

    private static final Pattern TITLE_PARSER = Pattern.compile("(.*)(\\s\\(\\d+\\).*)");

    @Data
    public static class BookmarkInfo {
        private String bookName;
        private String chapterTitle;
        private String url;
        private LocalDateTime updateTime;

        public BookmarkInfo(String bookName, String chapterTitle, String url, LocalDateTime updateTime) {
            this.bookName = bookName;
            this.chapterTitle = chapterTitle;
            this.url = url;
            this.updateTime = updateTime;
        }
    }

    @PostConstruct
    @Transactional
    public void initAndMigrate() {
        admins = Arrays.stream(adminList.split(",")).toList();
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 1.5))
    public void createBookmarkToken(String bookName, String chapterTitle, String url, String tokenStr) {
        BookmarkToken token = new BookmarkToken();
        token.setToken(tokenStr);
        token.setBookName(bookName);
        token.setChapterTitle(chapterTitle);
        token.setUrl(url);

        tokenRepo.save(token);
    }

    public void createBookmarkTokenInLocal(String bookName, String chapterTitle, String url) {
        String tokenStr = "bm_" + UUID.randomUUID().toString().substring(0, 8);

        BookmarkToken token = new BookmarkToken();
        token.setToken(tokenStr);
        token.setBookName(bookName);
        token.setChapterTitle(chapterTitle);
        token.setUrl(url);

        tokenRepo.save(token);
    }

    public BookmarkInfo getBookmarkByToken(String tokenStr) {
        return tokenRepo.findById(tokenStr)
                .map(t -> new BookmarkInfo(t.getBookName(), t.getChapterTitle(), t.getUrl(), LocalDateTime.now()))
                .orElse(null);
    }

    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void saveBookmarkForUser(Long userId, BookmarkInfo info) {
        UserBookmark bookmark = new UserBookmark();
        bookmark.setUserId(userId);
        bookmark.setBookName(info.getBookName());
        bookmark.setChapterTitle(info.getChapterTitle());
        bookmark.setUrl(info.getUrl());
        bookmark.setCreateTime(info.getUpdateTime());
        bookmark.setUpdateTime(info.getUpdateTime());
        bookmarkRepo.save(bookmark);
    }

    public List<BookmarkService.BookmarkInfo> getUserBookmarks(Long userId) {
        List<BookmarkService.BookmarkInfo> allBookmarks = new ArrayList<>();
        List<UserBookmark> byUserIdOrderByUpdateTimeDesc = bookmarkRepo.findByUserIdOrderByUpdateTimeDesc(userId);
        for (UserBookmark userBookmark : byUserIdOrderByUpdateTimeDesc) {
            allBookmarks.add(new BookmarkInfo(userBookmark.getBookName(), userBookmark.getChapterTitle(), userBookmark.getUrl(), userBookmark.getUpdateTime()));
        }

        return allBookmarks;
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

    public List<Map<String, String>> getAllBooksStructuredWithSearch(String searchTerm) {
        List<BookmarkToken> tokens;

        if (searchTerm != null && !searchTerm.isBlank()) {
            tokens = tokenRepo.searchBooks(searchTerm);
        } else {
            tokens = tokenRepo.findAllFirstChapters();
        }

        List<Map<String, String>> books = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);

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

    public boolean addBookmarkByToken(Long userId, String tokenStr) {
        BookmarkInfo info = getBookmarkByToken(tokenStr);

        if (info == null) {
            log.warn("Token not found or expired: {}", tokenStr);
            return false;
        }

        saveBookmarkForUser(userId, info);
        log.info("Bookmark saved for user: {}, book: {}", userId, info.getBookName());
        return true;
    }

    public String findTokenByPage(String bookId, int pageIndex) {
        String suffixRead = "/read/" + bookId + "/" + pageIndex;
        String suffixBooks = "/books/" + bookId + "/" + pageIndex;
        return tokenRepo.findByBookIdDual(suffixRead, suffixBooks)
                .stream()
                .findFirst()
                .map(BookmarkToken::getToken)
                .orElse(null);
    }

    @Transactional
    public boolean deleteBook(String bookName, Long userId) {
        try {
            if (admins.isEmpty() || admins.contains(String.valueOf(userId))) {

                try {
                    BookmarkToken token = tokenRepo.findFirstByBookName(bookName);

                    if (token != null && token.getUrl() != null) {
                        log.info("book token url: [{}]", token.getUrl());
                        String bookId = extractBookIdFromUrlFromLocalDB(token.getUrl());
                        if (bookId != null) {
                            log.info("正在删除本地书籍文件, BookName: {}, BookId: {}", bookName, bookId);
                            localBookService.deleteBookDirectory(bookId);
                        } else {
                            bookId = extractBookIdFromUrlFromR2(token.getUrl());
                            log.info("正在删除R2书籍文件, BookName: {}, BookId: {}", bookName, bookId);
                            r2StorageService.deleteFolder("books/" + bookId);
                        }
                    }
                } catch (Exception e) {
                    log.error("删除文件失败，但继续删除数据库记录", e);
                }
                tokenRepo.deleteByBookName(bookName);
                return true;
            } else {
                return false;
            }
        } catch (CannotAcquireLockException e) {
            log.error("database is locked!");
        }
        return false;
    }

    private String extractBookIdFromUrlFromLocalDB(String url) {
        try {
            Pattern pattern = Pattern.compile("/read/([^/]+)/");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("extractBookIdFromUrlFromLocalDB 解析 BookId 失败: {}", url);
        }
        return null;
    }

    private String extractBookIdFromUrlFromR2(String url) {
        try {
            Pattern pattern = Pattern.compile("/books/([^/]+)/");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("extractBookIdFromUrlFromR2 解析 BookId 失败: {}", url);
        }
        return null;
    }

    public int getTotalPages(String bookId) {
        String readPath = "/read/" + bookId + "/";
        String booksPath = "/books/" + bookId + "/";
        return tokenRepo.countByBookIdDual(readPath, booksPath);
    }

    public BookmarkToken findFirstByUrlContaining(String bookId) {
        String readPath = "/read/" + bookId + "/";
        String booksPath = "/books/" + bookId + "/";
        // 使用新的查询方法获取第一条记录（通常用于获取书名）
        Page<BookmarkToken> page = tokenRepo.findByBookIdDual(readPath, booksPath, PageRequest.of(0, 1));
        return page.hasContent() ? page.getContent().get(0) : null;
    }

    public Map<String, Object> getBookPages(String bookId, int page, int size) {
        String searchPath = "/read/" + bookId + "/";
        String booksPath = "/books/" + bookId + "/"; // 新模式 (R2)


        Page<BookmarkToken> pageResult = tokenRepo.findByBookIdDual(searchPath, booksPath, PageRequest.of(page, size));

        List<Map<String, Object>> list = pageResult.getContent().stream()
                .map(t -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("title", t.getChapterTitle());
                    map.put("url", t.getUrl());
                    map.put("page", extractPageIndexFromUrl(t.getUrl()));
                    return map;
                })
                .sorted(Comparator.comparingInt(m -> (Integer) m.get("page")))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages", pageResult.getTotalPages());
        return result;
    }

    private int extractPageIndexFromUrl(String url) {
        try {
            Pattern pattern = Pattern.compile("/(\\d+)(\\.html)?$");

            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }

            Pattern patternOld = Pattern.compile("/(\\d+)(/|$)");
            Matcher matcherOld = patternOld.matcher(url);
            if (matcherOld.find()) {
                return Integer.parseInt(matcherOld.group(1));
            }
        } catch (Exception ignore) {
        }
        return 0;
    }

    public BookmarkToken findBook(String bookName) {
        return tokenRepo.findFirstBookByBookName(bookName);
    }

    @Transactional(rollbackFor = Exception.class)
    public void renameBook(String userId, String oldName, String newName) {
        log.info("用户 [{}] 请求重命名书籍: [{}] -> [{}]", userId, oldName, newName);

        if (admins.isEmpty() || admins.contains(String.valueOf(userId))) {
            if (oldName == null || newName == null || oldName.trim().isEmpty() || newName.trim().isEmpty()) {
                throw new IllegalArgumentException("书名不能为空");
            }

            if (!tokenRepo.existsByBookName(oldName)) {
                throw new IllegalArgumentException("书籍 [" + oldName + "] 不存在，无法重命名");
            }

            if (tokenRepo.existsByBookName(newName)) {
                throw new IllegalArgumentException("书名 [" + newName + "] 已存在，请使用其他名称");
            }

            List<BookmarkToken> tokens = tokenRepo.findAllByBookName(oldName);

            for (BookmarkToken token : tokens) {
                token.setBookName(newName);

                String oldTitle = token.getChapterTitle();
                if (oldTitle != null) {
                    Matcher matcher = TITLE_PARSER.matcher(oldTitle);

                    if (matcher.matches()) {
                        String suffix = matcher.group(2);
                        token.setChapterTitle(newName + suffix);
                    } else {
                        token.setChapterTitle(newName);
                    }
                }
                // 如果一本书有 2000 页，你需要执行 2000 次下载 + 2000 次上传。
                // 该功能可能会消耗大量的网络带宽和 R2 的操作次数，不过一般书籍最多几百页，也不是特别多，免费版完全够用。
                String key = extractKeyByRegex(token.getUrl());
                r2StorageService.updateHtmlTitle(key, newName);
                cloudflareService.purgeCache(token.getUrl());
            }
            tokenRepo.saveAll(tokens);
        } else {
            throw new IllegalArgumentException("无权修改！");
        }
    }

    private String extractKeyByRegex(String fullUrl) {
        Pattern pattern = Pattern.compile("(books/.*)");
        Matcher matcher = pattern.matcher(fullUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}