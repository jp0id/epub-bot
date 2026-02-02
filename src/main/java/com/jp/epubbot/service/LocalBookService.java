package com.jp.epubbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class LocalBookService {

    private static final String DATA_DIR = "data/books";

    public LocalBookService() {
        new File(DATA_DIR).mkdirs();
    }

    public void saveChapter(String bookId, int pageIndex, String content) {
        try {
            Path dir = Paths.get(DATA_DIR, bookId);
            if (!Files.exists(dir)) Files.createDirectories(dir);

            Path file = dir.resolve(pageIndex + ".html");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存章节失败: {} - {}", bookId, pageIndex, e);
        }
    }

    public String getChapter(String bookId, int pageIndex) {
        try {
            Path file = Paths.get(DATA_DIR, bookId, pageIndex + ".html");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("读取章节失败", e);
        }
        return null;
    }

    public String saveImage(String bookId, String fileName, byte[] data) {
        try {
            Path dir = Paths.get(DATA_DIR, bookId, "images");
            if (!Files.exists(dir)) Files.createDirectories(dir);

            Path file = dir.resolve(fileName);
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                fos.write(data);
            }
            return "/books/" + bookId + "/images/" + fileName;
        } catch (IOException e) {
            log.error("保存图片失败", e);
            return null;
        }
    }

    public void deleteBookDirectory(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return;
        }
        try {
            Path dir = Paths.get(DATA_DIR, bookId);
            File directory = dir.toFile();

            if (directory.exists() && directory.isDirectory()) {
                boolean deleted = FileSystemUtils.deleteRecursively(directory);
                if (deleted) {
                    log.info("物理文件已删除: {}", dir.toAbsolutePath());
                } else {
                    log.warn("物理文件删除失败: {}", dir.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("删除书籍目录时发生异常: {}", bookId, e);
        }
    }
}