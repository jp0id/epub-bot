package com.jp.epubbot.repository;

import com.jp.epubbot.entity.BookmarkToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
/**
 * @Author: J.P
 * @Date: 2026/1/9 12:20
 */

public interface BookmarkTokenRepository extends JpaRepository<BookmarkToken, String> {

    // 查找包含书名且章节名包含"(1)"的记录，用于列出所有书籍
    // 原逻辑：title.contains(name) && title.contains("(1)")
    // 这里简化逻辑，假设每本书第一章都生成了 token
    @Query("SELECT b FROM BookmarkToken b WHERE b.chapterTitle LIKE %:search% OR b.bookName LIKE %:search%")
    List<BookmarkToken> searchBooks(String search);

    // 获取所有去重后的书籍列表（为了性能，最好只查第一章）
    @Query("SELECT DISTINCT b FROM BookmarkToken b WHERE b.chapterTitle LIKE '%(1)%'")
    List<BookmarkToken> findAllFirstChapters();
}