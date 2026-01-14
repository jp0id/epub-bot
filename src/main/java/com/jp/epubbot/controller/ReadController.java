package com.jp.epubbot.controller;

import com.jp.epubbot.service.BookmarkService;
import com.jp.epubbot.service.LocalBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
public class ReadController {

    private final LocalBookService localBookService;

    private final BookmarkService bookmarkService;


    @Value("${telegram.bot.username}")
    private String botUsername;

    @GetMapping("/read/{bookId}/{pageIndex}")
    public String readPage(@PathVariable String bookId,
                           @PathVariable int pageIndex,
                           Model model) {

        String content = localBookService.getChapter(bookId, pageIndex);
        if (content == null) {
            return "error/404";
        }

        String nextContent = localBookService.getChapter(bookId, pageIndex + 1);
        if (nextContent != null) {
            model.addAttribute("nextPageUrl", "/read/" + bookId + "/" + (pageIndex + 1));
        }

        // 1. 获取当前页面的 Token
        String token = bookmarkService.findTokenByPage(bookId, pageIndex);

        model.addAttribute("bookmarkToken", token);
        model.addAttribute("botUsername", botUsername);

        model.addAttribute("content", content);

        // 这里的 bookmarkLink 是旧逻辑，可以保留作为备用，但主要使用上面的 token
        String deepLinkParam = "loc_" + bookId + "_" + pageIndex;
        String bookmarkLink = "https://t.me/" + botUsername + "?start=" + deepLinkParam;
        model.addAttribute("bookmarkLink", bookmarkLink);

        return "read";
    }
}