package com.jp.epubbot.repository;

import com.jp.epubbot.entity.BookmarkToken;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:20
 */

public interface BookmarkTokenRepository extends JpaRepository<BookmarkToken, String> {

    @Query("SELECT b FROM BookmarkToken b WHERE b.chapterTitle LIKE %:search% OR b.bookName LIKE %:search%")
    List<BookmarkToken> searchBooks(String search);

    // 获取所有去重后的书籍列表, 只查第一页）
    @Query("SELECT DISTINCT b FROM BookmarkToken b WHERE b.chapterTitle LIKE '%(1)%'")
    List<BookmarkToken> findAllFirstChapters();

    @Modifying
    @Query("DELETE FROM BookmarkToken where bookName = :bookName")
    void deleteByBookName(String bookName);

    @Query("SELECT b FROM BookmarkToken b WHERE b.url LIKE %:urlSuffix")
    Optional<BookmarkToken> findByUrlSuffix(String urlSuffix);

    BookmarkToken findFirstByBookName(String bookName);

    @Query("SELECT COUNT(b) FROM BookmarkToken b WHERE b.url LIKE %:bookId%")
    int countByBookId(String bookId);


    Page<BookmarkToken> findByUrlContaining(String urlPart, Pageable pageable);

}