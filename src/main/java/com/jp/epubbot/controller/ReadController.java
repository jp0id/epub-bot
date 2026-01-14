package com.jp.epubbot.controller;

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

        String deepLinkParam = "loc_" + bookId + "_" + pageIndex;
        String bookmarkLink = "https://t.me/" + botUsername + "?start=" + deepLinkParam;

//        model.addAttribute("title", "阅读模式");
        model.addAttribute("content", content);
        model.addAttribute("bookmarkLink", bookmarkLink);

        return "read";
    }
}