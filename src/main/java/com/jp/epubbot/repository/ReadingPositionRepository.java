package com.jp.epubbot.repository;

import com.jp.epubbot.entity.ReadingPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:21
 */

public interface ReadingPositionRepository extends JpaRepository<ReadingPosition, Long> {
    Optional<ReadingPosition> findByUserIdAndBookName(Long userId, String bookName);
    List<ReadingPosition> findByUserId(Long userId);
    void deleteByUserIdAndBookName(Long userId, String bookName);
    void deleteByUserId(Long userId);
    List<ReadingPosition> findByUserIdOrderByTimestampDesc(Long userId);
}