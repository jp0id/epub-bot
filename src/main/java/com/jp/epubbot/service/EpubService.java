package com.jp.epubbot.service;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpubService {

    private final TelegraphService telegraphService;
    private final BookmarkService bookmarkService;

    @Value("${app.chars-per-page}")
    private int charsPerPage;

    @Value("${telegram.bot.username}")
    private String botUsername;

    public List<String> processEpub(InputStream epubStream, String bookTitle) throws Exception {
        EpubReader epubReader = new EpubReader();
        Book book = epubReader.readEpub(epubStream);
        String finalTitle = (book.getTitle() != null && !book.getTitle().isEmpty()) ? book.getTitle() : bookTitle;
        List<String> pageUrls = new ArrayList<>();
        List<Resource> contents = book.getContents();

        List<Map<String, Object>> currentBuffer = new ArrayList<>();
        int currentLength = 0;
        int pageCounter = 1;
        TelegraphService.PageResult previousPage = null;

        log.info("开始解析书籍: {}", finalTitle);

        for (Resource res : contents) {
            try {
                String html = new String(res.getData(), StandardCharsets.UTF_8);
                Document doc = Jsoup.parse(html);
                Element body = doc.body();
                List<Map<String, Object>> nodes = new ArrayList<>();
                flattenDom(body, nodes);

                for (Map<String, Object> node : nodes) {
                    currentBuffer.add(node);
                    currentLength += estimateLength(node);

                    if (currentLength >= charsPerPage) {
                        String pageTitle = finalTitle + " (" + pageCounter + ")";

                        TelegraphService.PageResult currentPage = telegraphService.createPage(pageTitle, currentBuffer);

                        if (currentPage != null) {
                            pageUrls.add(currentPage.getUrl());
                            String bookmarkToken = bookmarkService.createBookmarkToken(finalTitle, pageTitle, currentPage.getUrl());

                            if (previousPage != null) {
                                String prevToken = bookmarkService.createBookmarkToken(finalTitle, previousPage.getTitle(), previousPage.getUrl());
                                appendFooterLinks(previousPage, currentPage.getUrl(), prevToken, previousPage.getUsedToken());
                            }

                            previousPage = currentPage;
                        }
                        currentBuffer = new ArrayList<>();
                        currentLength = 0;
                        pageCounter++;
                    }
                }
            } catch (Exception e) {
                log.error("解析章节失败", e);
            }
        }

        // 最后一页
        if (!currentBuffer.isEmpty()) {
            String pageTitle = finalTitle + " (" + pageCounter + ") - End";
            TelegraphService.PageResult lastPage = telegraphService.createPage(pageTitle, currentBuffer);
            if (lastPage != null) {
                pageUrls.add(lastPage.getUrl());
                if (previousPage != null) {
                    String prevToken = bookmarkService.createBookmarkToken(finalTitle, previousPage.getTitle(), previousPage.getUrl());
                    appendFooterLinks(previousPage, lastPage.getUrl(), prevToken, previousPage.getUsedToken());
                }
                String lastToken = bookmarkService.createBookmarkToken(finalTitle, pageTitle, lastPage.getUrl());
                appendFooterLinks(lastPage, null, lastToken, lastPage.getUsedToken());
            }
        }

        return pageUrls;
    }

    private void appendFooterLinks(TelegraphService.PageResult pageToEdit, String nextUrl, String bookmarkToken, String tokenToUse) {
        try {
            List<Map<String, Object>> content = pageToEdit.getContent();

            Map<String, Object> hr = new HashMap<>();
            hr.put("tag", "hr");
            content.add(hr);

            List<Object> pChildren = new ArrayList<>();

            if (nextUrl != null) {
                Map<String, Object> nextLink = new HashMap<>();
                nextLink.put("tag", "a");
                nextLink.put("attrs", Map.of("href", nextUrl));
                nextLink.put("children", List.of("👉 下一章  "));
                pChildren.add(nextLink);
                pChildren.add("   |   ");
            }

            String deepLink = "https://t.me/" + botUsername + "?start=" + bookmarkToken;
            Map<String, Object> bmLink = new HashMap<>();
            bmLink.put("tag", "a");
            bmLink.put("attrs", Map.of("href", deepLink));
            bmLink.put("children", List.of("🔖 保存书签"));
            pChildren.add(bmLink);

            Map<String, Object> pWrapper = new HashMap<>();
            pWrapper.put("tag", "p");
            pWrapper.put("children", pChildren);
            content.add(pWrapper);

            telegraphService.editPage(pageToEdit.getPath(), pageToEdit.getTitle(), content, tokenToUse);

        } catch (Exception e) {
            log.warn("更新页脚失败: {}", e.getMessage());
        }
    }

    private void flattenDom(Node node, List<Map<String, Object>> result) {
        if (node instanceof Element element) {
            String tagName = element.tagName().toLowerCase();
            if (isContentBlock(tagName)) {
                Map<String, Object> converted = telegraphService.convertNode(element, false);
                if (converted != null) result.add(converted);
                return;
            }
            if (tagName.equals("br")) return;
            for (Node child : element.childNodes()) flattenDom(child, result);
        } else if (node instanceof TextNode) {
            String text = ((TextNode) node).text().trim();
            if (!text.isEmpty()) {
                Map<String, Object> pWrapper = Map.of("tag", "p", "children", List.of(text));
                result.add(pWrapper);
            }
        }
    }
    private boolean isContentBlock(String tagName) {
        return List.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "pre", "figure", "hr").contains(tagName);
    }
    private int estimateLength(Map<String, Object> node) {
        int len = 0;
        if (node.containsKey("children")) {
            List<?> children = (List<?>) node.get("children");
            for (Object child : children) {
                if (child instanceof String) len += ((String) child).length();
                else if (child instanceof Map) len += estimateLength((Map<String, Object>) child);
            }
        }
        return len == 0 ? 20 : len;
    }
}