package com.jp.epubbot.repository;

import com.jp.epubbot.entity.UserBookmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:21
 */

public interface UserBookmarkRepository extends JpaRepository<UserBookmark, Long> {
    List<UserBookmark> findByUserId(Long userId);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndUrl(Long userId, String url);
    List<UserBookmark> findByUserIdOrderByUpdateTimeDesc(Long userId);
}