package com.jp.epubbot.repository;

import com.jp.epubbot.entity.TelegraphAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:39
 */

public interface TelegraphAccountRepository extends JpaRepository<TelegraphAccount, String> {
}