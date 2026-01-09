package com.jp.epubbot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: J.P
 * @Date: 2026/1/9 12:38
 */

@Data
@Entity
@Table(name = "telegraph_accounts")
public class TelegraphAccount {

    @Id
    private String accessToken;

    private Long createdTime;

    // 如果需要，可以存储更多账户信息，如 auth_url, short_name 等
    // private String authUrl;
}