package com.jp.epubbot.service;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpubService {

    private final BookmarkService bookmarkService;
    private final LocalBookService localBookService;

    @Value("${app.chars-per-page:3000}")
    private int charsPerPage;

    @Value("${telegram.bot.webapp-url:}")
    private String webAppUrl;

    public List<String> processEpub(InputStream epubStream, String fileName) throws Exception {
        Book book;
        try {
            book = new EpubReader().readEpub(epubStream);
        } catch (Exception e) {
            log.warn("EPUB 解析遇到错误 (可能是图片损坏)，尝试容错模式加载: {}", e.getMessage());
            byte[] epubData = StreamUtils.copyToByteArray(epubStream);
            book = loadEpubLeniently(epubData);
        }

        String bookTitle = (book.getTitle() != null && !book.getTitle().isEmpty()) ? book.getTitle() : fileName;

        String bookId = UUID.randomUUID().toString().replace("-", "");

        List<String> pageUrls = new ArrayList<>();
        List<Resource> contents = book.getContents();

        StringBuilder currentHtmlBuffer = new StringBuilder();
        int currentLength = 0;
        int pageCounter = 1;

        log.info("开始解析书籍: {} (ID: {})", bookTitle, bookId);

        for (Resource res : contents) {
            try {
                String html = new String(res.getData(), StandardCharsets.UTF_8);
                Document doc = Jsoup.parse(html);
                Element body = doc.body();

                body.select("script, style, meta, link, title, iframe, head").remove();

                if (isContentEmpty(body)) continue;

                removeInvalidLinks(body);
                handleImagesLocal(doc, book, res.getHref(), bookId);

                for (Element child : body.children()) {
                    String childHtml = child.outerHtml();
                    int childLen = child.text().length();
                    if (currentLength + childLen > charsPerPage && currentLength > 0) {
                        savePage(bookId, pageCounter, currentHtmlBuffer.toString());
                        String pageUrl = generateUrl(bookId, pageCounter);
                        pageUrls.add(pageUrl);
                        bookmarkService.createBookmarkToken(bookTitle, bookTitle + " (" + pageCounter + ")", pageUrl);
                        currentHtmlBuffer.setLength(0);
                        currentLength = 0;
                        pageCounter++;
                    }
                    currentHtmlBuffer.append(childHtml);
                    currentLength += childLen;
                }
            } catch (Exception e) {
                log.error("解析章节失败: {}", res.getHref(), e);
            }
        }

        if (!currentHtmlBuffer.isEmpty()) {
            savePage(bookId, pageCounter, currentHtmlBuffer.toString());
            String pageUrl = generateUrl(bookId, pageCounter);
            pageUrls.add(pageUrl);
            bookmarkService.createBookmarkToken(bookTitle, bookTitle + " (" + pageCounter + ") - End", pageUrl);
        }

        return pageUrls;
    }

    /**
     * 容错加载：
     * 使用 ZipFile 而不是 ZipInputStream。
     * ZipFile 基于中央目录索引，即使中间有坏文件，也能跳过并读取后续的关键文件。
     */
    private Book loadEpubLeniently(byte[] data) throws IOException {
        File tempFile = File.createTempFile("epub_repair_" + UUID.randomUUID(), ".epub");

        try {
            Files.write(tempFile.toPath(), data);

            try (ZipFile zipFile = new ZipFile(tempFile);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ZipOutputStream zos = new ZipOutputStream(baos)) {

                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    try {
                        InputStream is = zipFile.getInputStream(entry);

                        ZipEntry newEntry = new ZipEntry(entry.getName());
                        zos.putNextEntry(newEntry);

                        try {
                            StreamUtils.copy(is, zos);
                        } catch (Exception e) {
                            log.warn("修复时丢弃损坏的文件内容: {}", entry.getName());
                        } finally {
                            is.close();
                        }

                        zos.closeEntry();
                    } catch (Exception e) {
                        log.warn("无法读取 ZIP 条目: {}, 跳过", entry.getName());
                        try { zos.closeEntry(); } catch (Exception ignored) {}
                    }
                }

                zos.finish();

                log.info("EPUB 结构重组完成，尝试重新解析...");
                return new EpubReader().readEpub(new ByteArrayInputStream(baos.toByteArray()));
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void savePage(String bookId, int pageIndex, String content) {
        localBookService.saveChapter(bookId, pageIndex, content);
    }

    private String generateUrl(String bookId, int pageIndex) {
        String base = webAppUrl;
        if (base == null || base.isEmpty()) {
            base = "http://localhost:18088";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/read/" + bookId + "/" + pageIndex;
    }

    private void handleImagesLocal(Document doc, Book book, String currentResourceHref, String bookId) {
        List<Element> images = new ArrayList<>(doc.select("img"));
        for (Element img : images) {
            String src = img.attr("src");
            if (src.startsWith("http")) continue;

            try {
                String imageHref = resolveHref(currentResourceHref, src);
                Resource imageRes = book.getResources().getByHref(imageHref);

                if (imageRes != null) {
                    byte[] data = imageRes.getData();

                    if (data.length < 1024 || isImageTooSmall(data)) {
                        img.remove();
                        continue;
                    }

                    String fileName = new File(src).getName();
                    String safeFileName = UUID.randomUUID().toString().substring(0, 8) + "_" + fileName.replaceAll("[^a-zA-Z0-9.-]", "_");

                    String localPath = localBookService.saveImage(bookId, safeFileName, data);

                    if (localPath != null) {
                        img.attr("src", localPath);
                        img.attr("style", "max-width: 100%; height: auto; display: block; margin: 10px auto;");
                    } else {
                        img.remove();
                    }
                } else {
                    img.remove();
                }
            } catch (Exception e) {
                log.warn("图片处理异常: {}", src);
                img.remove();
            }
        }
    }

    private boolean isImageTooSmall(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            BufferedImage bi = ImageIO.read(bais);
            if (bi == null) return false;
            return bi.getWidth() < 50 || bi.getHeight() < 50;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveHref(String baseHref, String relativeHref) {
        try {
            relativeHref = java.net.URLDecoder.decode(relativeHref, StandardCharsets.UTF_8);
            if (baseHref == null || baseHref.isEmpty()) return relativeHref;
            java.net.URI baseUri = new java.net.URI("file:///" + baseHref);
            java.net.URI resolvedUri = baseUri.resolve(relativeHref);
            String path = resolvedUri.getPath();
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return relativeHref;
        }
    }

    private boolean isContentEmpty(Element body) {
        if (body == null) return true;
        if (body.hasText() && !body.text().trim().isEmpty()) return false;
        if (!body.select("img").isEmpty()) return false;
        return body.select("svg").isEmpty();
    }

    private void removeInvalidLinks(Element body) {
        Elements links = body.select("a");
        for (Element link : links) {
            String href = link.attr("href");
            if (!href.startsWith("http://") && !href.startsWith("https://") && !href.startsWith("mailto:")) {

                link.unwrap();
            }
            // 外部链接也去掉的话，直接去掉 if 判断，对所有 link 执行 unwrap() 即可
        }
    }
}