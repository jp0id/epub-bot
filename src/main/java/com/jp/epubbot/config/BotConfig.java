package com.jp.epubbot.config;

import com.jp.epubbot.service.BookBot;
import com.jp.epubbot.service.BookmarkService;
import com.jp.epubbot.service.EpubService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

@Configuration
public class BotConfig {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.admins:}")
    private String adminList;

    @Value("${telegram.bot.base:https://api.telegram.org}")
    private String baseUrl;

    @Bean
    public DefaultBotOptions defaultBotOptions() {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setBaseUrl(baseUrl);
        return options;
    }

    @Bean
    public BookBot bookBot(DefaultBotOptions options, EpubService epubService, BookmarkService bookmarkService) {
        return new BookBot(options, botToken, botUsername, epubService, bookmarkService, adminList);
    }

    @Bean
    public TelegramBotsApi telegramBotsApi(BookBot bookBot) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bookBot);
        return api;
    }
}