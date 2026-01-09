package com.jp.epubbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class EpubBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(EpubBotApplication.class, args);
    }
}
