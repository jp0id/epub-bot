package com.jp.epubbot.controller;

import com.jp.epubbot.entity.BookmarkToken;
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

    @GetMapping("/read/{bookId}/{pageIndex:\\d+}")
    public String readPage(@PathVariable String bookId,
                           @PathVariable int pageIndex,
                           Model model) {

        String content = localBookService.getChapter(bookId, pageIndex);
        if (content == null) {
            return "error/404";
        }

        int totalPages = bookmarkService.getTotalPages(bookId);
        model.addAttribute("totalPages", totalPages);

        String nextContent = localBookService.getChapter(bookId, pageIndex + 1);
        if (nextContent != null) {
            model.addAttribute("nextPageUrl", "/read/" + bookId + "/" + (pageIndex + 1));
        }

        String token = bookmarkService.findTokenByPage(bookId, pageIndex);

        BookmarkToken first = bookmarkService.findFirstByUrlContaining(bookId);

        model.addAttribute("bookmarkToken", token);
        model.addAttribute("botUsername", botUsername);

        model.addAttribute("content", content);

        String deepLinkParam = "loc_" + bookId + "_" + pageIndex;
        String bookmarkLink = "https://t.me/" + botUsername + "?start=" + deepLinkParam;
        model.addAttribute("bookmarkLink", bookmarkLink);

        String name = first.getBookName();
        model.addAttribute("title", (name != null && name.length() > 10) ? name.substring(0, 10) + "..." : name);

        return "read";
    }
}