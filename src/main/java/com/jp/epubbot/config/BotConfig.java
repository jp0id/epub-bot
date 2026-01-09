package com.jp.epubbot.config;

import com.jp.epubbot.service.BookBot;
import com.jp.epubbot.service.BookmarkService;
import com.jp.epubbot.service.EpubService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.Collections;

@Configuration
public class BotConfig {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.admins:}")
    private String adminList;

    @Value("${telegram.bot.base:}")
    private String baseUrl;

    @Value("${telegram.bot.webapp-url:}")
    private String webappUrl;

    @Bean
    public DefaultBotOptions defaultBotOptions() {
        DefaultBotOptions options = new DefaultBotOptions();
        if (StringUtils.isNotEmpty(baseUrl)) {
            options.setBaseUrl(baseUrl);
        }
        return options;
    }

    @Bean
    public BookBot bookBot(DefaultBotOptions options, EpubService epubService, BookmarkService bookmarkService) {
        return new BookBot(options, botToken, botUsername, epubService, bookmarkService, adminList, webappUrl);
    }

    @Bean
    public TelegramBotsApi telegramBotsApi(BookBot bookBot) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bookBot);
        return api;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Collections.singletonList("*"));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}