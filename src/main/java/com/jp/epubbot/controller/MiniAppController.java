package com.jp.epubbot.controller;

import com.jp.epubbot.service.BookmarkService;
import lombok.Data;
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
    public static class BookInfo {
        private String id;
        private String name;
        private String url;
        private String firstPageTitle;
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
        info.put("description", "Telegram Mini App for EPUB reading bot");
        info.put("features", new String[]{
                "Test web interface",
                "Book browsing",
                "Future: Reading interface",
                "Future: Bookmark management"
        });
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

            // Validate page number
            if (page < 0) page = 0;
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;

            // Calculate pagination bounds
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

            // Group bookmarks by book name
            Map<String, List<BookmarkService.BookmarkInfo>> bookGroups = new TreeMap<>();
            for (BookmarkService.BookmarkInfo bookmark : allBookmarks) {
                String bookName = bookmark.getBookName();
                if (bookName == null) bookName = "未知书籍";
                bookGroups.computeIfAbsent(bookName, k -> new ArrayList<>()).add(bookmark);
            }

            // Calculate total bookmarks count
            int totalBookmarks = allBookmarks.size();
            // For pagination, we need to count by groups to keep groups intact
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

            // Calculate pagination for groups (keeping groups intact)
            int currentBookmarkCount = 0;
            int currentPage = 0;
            int startGroupIndex = 0;
            int endGroupIndex = 0;
            Map<Integer, Map<String, Integer>> pageMap = new HashMap<>(); // page -> {startGroup, endGroup}

            for (int i = 0; i < allGroups.size(); i++) {
                Map<String, Object> group = allGroups.get(i);
                int groupBookmarkCount = (int) group.get("count");

                // If adding this group would exceed page size and we already have some bookmarks on this page
                if (currentBookmarkCount + groupBookmarkCount > size && currentBookmarkCount > 0) {
                    // End current page
                    pageMap.put(currentPage, Map.of(
                        "startGroup", startGroupIndex,
                        "endGroup", i - 1
                    ));
                    // Start new page
                    currentPage++;
                    currentBookmarkCount = 0;
                    startGroupIndex = i;
                }

                currentBookmarkCount += groupBookmarkCount;
                // If exactly filled the page
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

            // Handle last page
            if (currentBookmarkCount > 0 || startGroupIndex < allGroups.size()) {
                if (startGroupIndex < allGroups.size()) {
                    pageMap.put(currentPage, Map.of(
                        "startGroup", startGroupIndex,
                        "endGroup", allGroups.size() - 1
                    ));
                }
            }

            int totalPages = pageMap.size();

            // Validate page number
            if (page < 0) page = 0;
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;

            // Get groups for current page
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

    // ==================== 阅读位置相关API ====================

    /**
     * 保存或更新阅读位置
     */
    @PostMapping("/reading-positions")
    public Map<String, Object> saveReadingPosition(
            Long userId,
            @RequestBody BookmarkService.ReadingPosition position) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }
            if (position == null || position.getBookName() == null) {
                response.put("success", false);
                response.put("error", "Reading position data is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            bookmarkService.saveReadingPosition(userId, position);
            response.put("success", true);
            response.put("message", "Reading position saved successfully");
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to save reading position", e);
            response.put("success", false);
            response.put("error", "Failed to save reading position");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    /**
     * 获取用户的所有阅读位置
     */
    @GetMapping("/reading-positions")
    public Map<String, Object> getAllReadingPositions(Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            List<BookmarkService.ReadingPosition> positions = bookmarkService.getAllReadingPositions(userId);
            response.put("success", true);
            response.put("positions", positions);
            response.put("count", positions.size());
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to get reading positions", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve reading positions");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    /**
     * 获取用户的特定书籍阅读位置
     */
    @GetMapping("/reading-positions/{bookName}")
    public Map<String, Object> getReadingPosition(
            Long userId,
            @PathVariable String bookName) {
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

            BookmarkService.ReadingPosition position = bookmarkService.getReadingPosition(userId, bookName);
            if (position != null) {
                response.put("success", true);
                response.put("position", position);
                response.put("timestamp", System.currentTimeMillis());
            } else {
                response.put("success", false);
                response.put("error", "Reading position not found");
                response.put("timestamp", System.currentTimeMillis());
            }

        } catch (Exception e) {
            log.error("Failed to get reading position", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve reading position");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    /**
     * 删除用户的特定书籍阅读位置
     */
    @DeleteMapping("/reading-positions/{bookName}")
    public Map<String, Object> deleteReadingPosition(
            Long userId,
            @PathVariable String bookName) {
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

            boolean deleted = bookmarkService.deleteReadingPosition(userId, bookName);
            if (deleted) {
                response.put("success", true);
                response.put("message", "Reading position deleted successfully");
                response.put("timestamp", System.currentTimeMillis());
            } else {
                response.put("success", false);
                response.put("error", "Reading position not found");
                response.put("timestamp", System.currentTimeMillis());
            }

        } catch (Exception e) {
            log.error("Failed to delete reading position", e);
            response.put("success", false);
            response.put("error", "Failed to delete reading position");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    /**
     * 清除用户的所有阅读位置
     */
    @DeleteMapping("/reading-positions")
    public Map<String, Object> clearReadingPositions(Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            bookmarkService.clearReadingPositions(userId);
            response.put("success", true);
            response.put("message", "All reading positions cleared successfully");
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to clear reading positions", e);
            response.put("success", false);
            response.put("error", "Failed to clear reading positions");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }

    /**
     * 获取用户的阅读统计信息
     */
    @GetMapping("/reading-positions/stats")
    public Map<String, Object> getReadingStats(Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (userId == null) {
                response.put("success", false);
                response.put("error", "User ID is required");
                response.put("timestamp", System.currentTimeMillis());
                return response;
            }

            Map<String, Object> stats = bookmarkService.getReadingStats(userId);
            response.put("success", true);
            response.put("stats", stats);
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to get reading stats", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve reading stats");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }
}