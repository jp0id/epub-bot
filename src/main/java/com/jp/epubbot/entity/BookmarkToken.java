package com.jp.epubbot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:18
 */
@Data
@Entity
@Table(name = "bookmark_tokens", indexes = {
        @Index(name = "idx_token", columnList = "token"),
        @Index(name = "idx_book_name", columnList = "bookName") // 用于搜索书籍列表
})
public class BookmarkToken {
    @Id
    private String token; // Token 本身作为主键

    private String bookName;
    private String chapterTitle;
    private String url;
}