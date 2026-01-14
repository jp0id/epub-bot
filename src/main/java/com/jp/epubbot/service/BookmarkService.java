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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
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

    private final BookmarkTokenRepository tokenRepo;
    private final UserBookmarkRepository bookmarkRepo;
    private final LocalBookService localBookService;

    private List<String> admins;

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
        admins = Arrays.stream(adminList.split(",")).toList();
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public void createBookmarkToken(String bookName, String chapterTitle, String url) {
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
        // 构造 URL 后缀，例如: /read/abc12345/1
        String suffix = "/read/" + bookId + "/" + pageIndex;
        return tokenRepo.findByUrlSuffix(suffix)
                .map(BookmarkToken::getToken)
                .orElse(null);
    }

    @Transactional
    public boolean deleteBook(String bookName, Long userId) {
        // 鉴权逻辑
        if (admins.isEmpty() || admins.contains(String.valueOf(userId))) {

            try {
                BookmarkToken token = tokenRepo.findFirstByBookName(bookName);

                if (token != null && token.getUrl() != null) {
                    String bookId = extractBookIdFromUrl(token.getUrl());

                    if (bookId != null) {
                        log.info("正在删除书籍文件, BookName: {}, BookId: {}", bookName, bookId);
                        localBookService.deleteBookDirectory(bookId);
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
    }

    private String extractBookIdFromUrl(String url) {
        try {
            Pattern pattern = Pattern.compile("/read/([^/]+)/");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("解析 BookId 失败: {}", url);
        }
        return null;
    }

}