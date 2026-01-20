package com.jp.epubbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableRetry
public class EpubBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(EpubBotApplication.class, args);
    }
}
