package com.jp.epubbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping("/app")
    public String app() {
        return "forward:/index.html";
    }

    @GetMapping("/telegram/app")
    public String telegramApp() {
        return "forward:/index.html";
    }
}