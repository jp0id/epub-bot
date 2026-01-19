package com.jp.epubbot.controller;

import com.jp.epubbot.service.BookmarkService;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/miniapp")
@RequiredArgsConstructor
@CrossOrigin
public class MiniAppController {

    private final BookmarkService bookmarkService;

    @Data
    public static class BookmarkRequest {
        private Long userId;
        private String token;
    }


    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "epub-bot-miniapp");
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    @GetMapping("/info")
    public Map<String, Object> getAppInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "EPUB Bot Mini App");
        info.put("version", "1.0.0");
        info.put("description", "Telegram EPUB阅读机器人");
        info.put("author", "pm_jp_bot");
        return info;
    }

    @GetMapping("/validate")
    public Map<String, Object> validateInitData() {
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("message", "Validation endpoint - implement Telegram initData validation");
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    @GetMapping("/books")
    public Map<String, Object> getAllBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        Map<String, Object> response = new HashMap<>();

        try {
            List<Map<String, String>> allBooksData = bookmarkService.getAllBooksStructuredWithSearch(search);
            int totalCount = allBooksData.size();
            int totalPages = (int) Math.ceil((double) totalCount / size);

            if (page < 0) page = 0;
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;

            int fromIndex = page * size;
            int toIndex = Math.min(fromIndex + size, totalCount);

            List<Map<String, String>> pageData = new ArrayList<>();
            if (fromIndex < totalCount) {
                pageData = allBooksData.subList(fromIndex, toIndex);
            }

            response.put("success", true);
            response.put("totalCount", totalCount);
            response.put("totalPages", totalPages);
            response.put("currentPage", page);
            response.put("pageSize", size);
            response.put("count", pageData.size());
            response.put("books", pageData);
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to get books list", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve books list");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    @DeleteMapping("/books")
    public Map<String, Object> deleteBook(@RequestParam String bookName, @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            if (bookName == null || bookName.isBlank()) {
                response.put("success", false);
                response.put("error", "Book name is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }
            log.info("Request to delete book: [{}], userId: [{}]", bookName, userId);
            boolean isDone = bookmarkService.deleteBook(bookName, userId);
            if (isDone) {
                response.put("success", true);
                response.put("message", "Book deleted successfully");
            } else {
                response.put("success", false);
                response.put("message", "You can't delete book! ");
            }
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to delete book: {}", bookName, e);
            response.put("success", false);
            response.put("error", "Failed to delete book: " + e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
        }
        return response;
    }

    @GetMapping("/bookmarks")
    public Map<String, Object> getUserBookmarks(
            Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> response = new TreeMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            List<BookmarkService.BookmarkInfo> allBookmarks = bookmarkService.getUserBookmarks(userId);

            Map<String, List<BookmarkService.BookmarkInfo>> bookGroups = new TreeMap<>();
            for (BookmarkService.BookmarkInfo bookmark : allBookmarks) {
                String bookName = bookmark.getBookName();
                if (bookName == null) bookName = "未知书籍";
                bookGroups.computeIfAbsent(bookName, k -> new ArrayList<>()).add(bookmark);
            }

            int totalBookmarks = allBookmarks.size();
            List<Map<String, Object>> allGroups = getAllGroups(bookGroups);

            int currentBookmarkCount = 0;
            int currentPage = 0;
            int startGroupIndex = 0;
            int endGroupIndex = 0;
            Map<Integer, Map<String, Integer>> pageMap = new HashMap<>(); // page -> {startGroup, endGroup}

            for (int i = 0; i < allGroups.size(); i++) {
                Map<String, Object> group = allGroups.get(i);
                int groupBookmarkCount = (int) group.get("count");

                if (currentBookmarkCount + groupBookmarkCount > size && currentBookmarkCount > 0) {
                    pageMap.put(currentPage, Map.of(
                            "startGroup", startGroupIndex,
                            "endGroup", i - 1
                    ));
                    currentPage++;
                    currentBookmarkCount = 0;
                    startGroupIndex = i;
                }

                currentBookmarkCount += groupBookmarkCount;
                if (currentBookmarkCount == size) {
                    pageMap.put(currentPage, Map.of(
                            "startGroup", startGroupIndex,
                            "endGroup", i
                    ));
                    currentPage++;
                    currentBookmarkCount = 0;
                    startGroupIndex = i + 1;
                }
            }

            if (currentBookmarkCount > 0 || startGroupIndex < allGroups.size()) {
                if (startGroupIndex < allGroups.size()) {
                    pageMap.put(currentPage, Map.of(
                            "startGroup", startGroupIndex,
                            "endGroup", allGroups.size() - 1
                    ));
                }
            }

            int totalPages = pageMap.size();

            if (page < 0) page = 0;
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;

            List<Map<String, Object>> pageGroups = new ArrayList<>();
            int pageBookmarkCount = 0;
            if (totalPages > 0) {
                Map<String, Integer> pageRange = pageMap.get(page);
                int startIdx = pageRange.get("startGroup");
                int endIdx = pageRange.get("endGroup");
                for (int i = startIdx; i <= endIdx; i++) {
                    pageGroups.add(allGroups.get(i));
                    pageBookmarkCount += (int) allGroups.get(i).get("count");
                }
            }

            response.put("success", true);
            response.put("totalBookmarks", totalBookmarks);
            response.put("totalPages", totalPages);
            response.put("currentPage", page);
            response.put("pageSize", size);
            response.put("pageBookmarkCount", pageBookmarkCount);
            response.put("groups", pageGroups);
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to get user bookmarks", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve bookmarks");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    @GetMapping("/bookmarks/clear")
    public Map<String, Object> clearUserBookmarks(Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            bookmarkService.clearBookmarks(userId);
            response.put("success", true);
            response.put("message", "Bookmarks cleared successfully");
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to clear user bookmarks", e);
            response.put("success", false);
            response.put("error", "Failed to clear bookmarks");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    @DeleteMapping("/bookmarks")
    public Map<String, Object> deleteBookmark(Long userId, String url) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }
            if (url == null || url.isBlank()) {
                response.put("success", false);
                response.put("error", "URL is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            log.info("Deleting bookmark for userId: {}, url: {}", userId, url);
            boolean deleted = bookmarkService.deleteBookmarkForUser(userId, url);
            if (deleted) {
                response.put("success", true);
                response.put("message", "Bookmark deleted successfully");
                response.put("timestamp", System.currentTimeMillis());
            } else {
                response.put("success", false);
                response.put("error", "Bookmark not found");
                response.put("timestamp", System.currentTimeMillis());
            }

        } catch (Exception e) {
            log.error("Failed to delete bookmark", e);
            response.put("success", false);
            response.put("error", "Failed to delete bookmark");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    @PostMapping("/bookmark")
    public Map<String, Object> saveBookmark(@RequestBody Map<String, Object> payload) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());

        try {
            Object userIdObj = payload.get("userId");
            if (userIdObj == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                return response;
            }
            Long userId = Long.parseLong(userIdObj.toString());

            String token = (String) payload.get("token");

            if (token != null && !token.isBlank() && !"DIRECT_URL_SAVE".equals(token)) {
                boolean saved = bookmarkService.addBookmarkByToken(userId, token);
                if (saved) {
                    response.put("success", true);
                    response.put("message", "Bookmark saved successfully (Token)");
                } else {
                    response.put("success", false);
                    response.put("error", "Invalid or expired token");
                }
                return response;
            }

            if (payload.containsKey("directData")) {
                Map<String, String> data = (Map<String, String>) payload.get("directData");

                String bookName = data.get("bookName");
                String chapterTitle = data.get("chapterTitle");
                String url = data.get("url");

                if (bookName == null || url == null) {
                    response.put("success", false);
                    response.put("error", "Incomplete bookmark data");
                    return response;
                }

                BookmarkService.BookmarkInfo info = new BookmarkService.BookmarkInfo(
                        bookName,
                        chapterTitle != null ? chapterTitle : "未知章节",
                        url
                );

                bookmarkService.saveBookmarkForUser(userId, info);

                response.put("success", true);
                response.put("message", "Bookmark saved successfully (Direct)");
                return response;
            }

            response.put("success", false);
            response.put("error", "Invalid request parameters: missing token or directData");

        } catch (Exception e) {
            log.error("Error saving bookmark", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
        }

        return response;
    }

    @GetMapping("/book/pages")
    public Map<String, Object> getBookPages(
            @RequestParam String bookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Map<String, Object> response = new HashMap<>();
        try {
            if (bookId == null || bookId.isBlank()) {
                response.put("success", false);
                response.put("error", "Book ID is required");
                return response;
            }

            Map<String, Object> result = bookmarkService.getBookPages(bookId, page, size);

            response.put("success", true);
            response.put("count", result.get("totalElements"));
            response.put("totalPages", result.get("totalPages"));
            response.put("currentPage", page);
            response.put("pages", result.get("list"));

        } catch (Exception e) {
            log.error("Failed to get book pages", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve pages");
        }
        return response;
    }


    private static @NonNull List<Map<String, Object>> getAllGroups(Map<String, List<BookmarkService.BookmarkInfo>> bookGroups) {
        List<Map<String, Object>> allGroups = new ArrayList<>();
        for (Map.Entry<String, List<BookmarkService.BookmarkInfo>> entry : bookGroups.entrySet()) {
            Map<String, Object> group = new TreeMap<>();
            group.put("bookName", entry.getKey());

            List<Map<String, String>> groupBookmarks = new ArrayList<>();
            for (BookmarkService.BookmarkInfo bookmark : entry.getValue()) {
                Map<String, String> bookmarkMap = new HashMap<>();
                bookmarkMap.put("bookName", bookmark.getBookName());
                bookmarkMap.put("chapterTitle", bookmark.getChapterTitle());
                bookmarkMap.put("url", bookmark.getUrl());
                groupBookmarks.add(bookmarkMap);
            }
            group.put("bookmarks", groupBookmarks);
            group.put("count", groupBookmarks.size());
            allGroups.add(group);
        }
        return allGroups;
    }

}