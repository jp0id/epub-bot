package com.jp.epubbot.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:19
 */
@Data
@Entity
@Table(name = "user_bookmarks", indexes = {
        @Index(name = "idx_user_id", columnList = "userId")
})
public class UserBookmark {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String bookName;
    private String chapterTitle;
    private String url;
}