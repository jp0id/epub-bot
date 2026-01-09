package com.jp.epubbot.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "reading_positions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"userId", "bookName"})
})
public class ReadingPosition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String bookName;
    private String chapterTitle;
    private String url;
    private String position;
    private Double progress;
    private Long timestamp;
}