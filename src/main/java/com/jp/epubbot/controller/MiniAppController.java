package com.jp.epubbot.controller;

import com.jp.epubbot.service.BookmarkService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/miniapp")
@RequiredArgsConstructor
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
    public Map<String, Object> getAllBooks() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<Map<String, String>> booksData = bookmarkService.getAllBooksStructured();

            response.put("success", true);
            response.put("count", booksData.size());
            response.put("books", booksData);
            response.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed to get books list", e);
            response.put("success", false);
            response.put("error", "Failed to retrieve books list");
            response.put("timestamp", System.currentTimeMillis());
        }

        return response;
    }
}