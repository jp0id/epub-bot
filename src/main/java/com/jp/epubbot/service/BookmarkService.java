package com.jp.epubbot.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BookmarkService {

    private final Map<String, BookmarkInfo> tokenMap = new ConcurrentHashMap<>();

    private final Map<Long, List<BookmarkInfo>> userBookmarks = new ConcurrentHashMap<>();

    @Data
    public static class BookmarkInfo {
        private String bookName;
        private String chapterTitle;
        private String url;
    }

    public String createBookmarkToken(String bookName, String chapterTitle, String url) {
        String token = "bm_" + UUID.randomUUID().toString().substring(0, 8);
        BookmarkInfo info = new BookmarkInfo();
        info.setBookName(bookName);
        info.setChapterTitle(chapterTitle);
        info.setUrl(url);

        tokenMap.put(token, info);
        return token;
    }

    public BookmarkInfo getBookmarkByToken(String token) {
        return tokenMap.get(token);
    }

    public void saveBookmarkForUser(Long userId, BookmarkInfo info) {
        userBookmarks.computeIfAbsent(userId, k -> new ArrayList<>()).add(info);
    }

    public List<BookmarkInfo> getUserBookmarks(Long userId) {
        return userBookmarks.getOrDefault(userId, Collections.emptyList());
    }

    public void clearBookmarks(Long userId) {
        userBookmarks.remove(userId);
    }
}