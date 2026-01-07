package com.jp.epubbot.service;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BookBot extends TelegramLongPollingBot {

    private final EpubService epubService;
    private final BookmarkService bookmarkService;
    private final String botUsername;
    private final Set<Long> processingUsers = ConcurrentHashMap.newKeySet();
    private List<String> admin = null;

    public BookBot(DefaultBotOptions options, String botToken, String botUsername,
                   EpubService epubService, BookmarkService bookmarkService, String adminList) {
        super(options, botToken);
        this.botUsername = botUsername;
        this.epubService = epubService;
        this.bookmarkService = bookmarkService;
        if (StringUtils.isNotEmpty(adminList)) {
            this.admin = Arrays.stream(adminList.split(",")).toList();
        }

        try {
            User me = execute(new GetMe());
            log.info("✅ Bot 启动成功: {}", me.getFirstName());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Bot 连接失败", e);
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            if (update.getMessage().hasDocument()) {
                if (admin != null && !admin.contains(String.valueOf(chatId))) {
                    sendText(chatId, "无权上传书籍! 请联系管理员 @pm_jp_bot");
                    return;
                }
                Document doc = update.getMessage().getDocument();
                if (doc.getFileName() != null && doc.getFileName().toLowerCase().endsWith(".epub")) {
                    handleEpubFile(chatId, doc);
                } else {
                    sendText(chatId, "请发送 .epub 格式的文件。");
                }
                return;
            }

            if (text != null) {
                if (text.startsWith("/start")) {
                    handleStartCommand(chatId, text);
                } else if (text.equals("/bookmarks")) {
                    handleListBookmarks(chatId);
                } else if (text.equals("/clear_bookmarks")) {
                    bookmarkService.clearBookmarks(chatId);
                    sendText(chatId, "🗑️ 书签已清空。");
                } else if (text.equals("/list")) {
                    sendText(chatId, bookmarkService.findAllBooks());
                } else {
                    sendText(chatId, "不支持的命令！");
                }
            }
        }
    }

    private void handleStartCommand(Long chatId, String text) {
        String[] parts = text.split(" ");
        if (parts.length > 1 && parts[1].startsWith("bm_")) {
            String token = parts[1];
            BookmarkService.BookmarkInfo info = bookmarkService.getBookmarkByToken(token);

            if (info != null) {
                bookmarkService.saveBookmarkForUser(chatId, info);
                sendText(chatId, "✅ **书签已保存！**\n\n📖 书名: " + info.getBookName() + "\n📑 章节: " + info.getChapterTitle() + "\n\n输入 /bookmarks 查看所有书签。");
            } else {
                sendText(chatId, "❌ 书签链接已失效或不存在。");
            }
        } else {
            sendText(chatId, "欢迎！\n1. 发送 EPUB 文件开始阅读。\n2. 阅读时点击底部的“保存书签”。\n3. 输入 /bookmarks 查看书签。");
        }
    }

    private void handleListBookmarks(Long chatId) {
        List<BookmarkService.BookmarkInfo> bookmarks = bookmarkService.getUserBookmarks(chatId);
        if (bookmarks.isEmpty()) {
            sendText(chatId, "📭 你还没有保存任何书签。");
            return;
        }

        StringBuilder sb = new StringBuilder("🔖 **我的书签**\n\n");
        for (int i = 0; i < bookmarks.size(); i++) {
            BookmarkService.BookmarkInfo bm = bookmarks.get(i);
            sb.append(i + 1).append(". [").append(bm.getChapterTitle()).append("](").append(bm.getUrl()).append(")\n");
            sb.append("   📖 ").append(bm.getBookName()).append("\n\n");
        }

        // 简单的长度截断，防止消息过长
        if (sb.length() > 4000) {
            sendText(chatId, sb.substring(0, 3500) + "\n... (列表过长，仅显示部分)");
        } else {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(sb.toString());
            msg.setParseMode("Markdown");
            msg.setDisableWebPagePreview(true);
            try {
                execute(msg);
            } catch (TelegramApiException e) {
                log.error("发送书签失败", e);
            }
        }
    }

    private void handleEpubFile(Long chatId, Document doc) {
        if (processingUsers.contains(chatId)) {
            sendText(chatId, "⚠️ 上一本书正在处理中，请稍候...");
            return;
        }
        processingUsers.add(chatId);
        sendText(chatId, "📚 收到书籍: " + doc.getFileName() + "\n正在处理，请稍候...");

        CompletableFuture.runAsync(() -> {
            try {
                org.telegram.telegrambots.meta.api.methods.GetFile getFile = new org.telegram.telegrambots.meta.api.methods.GetFile();
                getFile.setFileId(doc.getFileId());
                org.telegram.telegrambots.meta.api.objects.File file = execute(getFile);
                String fileUrl = file.getFileUrl(getBotToken());

                try (InputStream in = new URL(fileUrl).openStream()) {
                    List<String> links = epubService.processEpub(in, doc.getFileName());
                    if (links.isEmpty()) {
                        sendText(chatId, "❌ 解析失败或内容为空。");
                    } else {
                        StringBuilder sb = new StringBuilder("✅ **处理完成！**\n");
                        sb.append("共 ").append(links.size()).append(" 章。\n\n");
                        sb.append("📖 [点击开始阅读](").append(links.get(0)).append(")\n\n");
                        sb.append("💡 阅读时点击底部的 **[保存书签]** 即可记录进度。");

                        SendMessage message = new SendMessage();
                        message.setChatId(chatId);
                        message.setText(sb.toString());
                        message.setParseMode("Markdown");
                        message.setDisableWebPagePreview(true);
                        execute(message);
                    }
                }
            } catch (Exception e) {
                log.error("Error", e);
                sendText(chatId, "❌ 错误: " + e.getMessage());
            } finally {
                processingUsers.remove(chatId);
            }
        });
    }

    private void sendText(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");
        message.setDisableWebPagePreview(true);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Send failed", e);
        }
    }
}