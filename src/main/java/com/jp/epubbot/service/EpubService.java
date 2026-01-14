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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpubService {

    private final BookmarkService bookmarkService;
    private final LocalBookService localBookService;

    @Value("${app.chars-per-page:3000}")
    private int charsPerPage;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.webapp-url:}")
    private String webAppUrl;

    public List<String> processEpub(InputStream epubStream, String fileName) throws Exception {
        EpubReader epubReader = new EpubReader();
        Book book = epubReader.readEpub(epubStream);
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
                if (isContentEmpty(body)) {
                    log.info("跳过空章节: {} (无有效内容)", res.getHref());
                    continue;
                }
                removeInvalidLinks(body);
                handleImagesLocal(doc, book, res.getHref(), bookId);
                body.html();
                for (Element child : body.children()) {
                    String childHtml = child.outerHtml();
                    int childLen = child.text().length();
                    if (currentLength + childLen > charsPerPage && currentLength > 0) {
                        savePage(bookId, pageCounter, bookTitle, currentHtmlBuffer.toString());
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
                log.error("解析章节失败", e);
            }
        }

        if (!currentHtmlBuffer.isEmpty()) {
            savePage(bookId, pageCounter, bookTitle, currentHtmlBuffer.toString());
            String pageUrl = generateUrl(bookId, pageCounter);
            pageUrls.add(pageUrl);
            bookmarkService.createBookmarkToken(bookTitle, bookTitle + " (" + pageCounter + ") - End", pageUrl);
        }
        log.info("解析书籍: {} (ID: {}) 完成", bookTitle, bookId);
        return pageUrls;
    }

    private void savePage(String bookId, int pageIndex, String title, String content) {
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

                    if (data.length < 1024) {
                        img.remove();
                        continue;
                    }

                    if (isImageTooSmall(data)) {
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

    /**
     * 辅助方法：判断图片尺寸是否太小
     */
    private boolean isImageTooSmall(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            BufferedImage bi = ImageIO.read(bais);
            if (bi == null) {
                return false;
            }
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
        if (body.hasText()) {
            if (!body.text().trim().isEmpty()) {
                return false;
            }
        }
        if (!body.select("img").isEmpty()) {
            return false;
        }
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
