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
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
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

    @Value("${app.chars-per-page:3000}")
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
        String previousBookmarkToken = null;

        Map<String, String> uploadedImagesCache = new HashMap<>();

        log.info("开始解析书籍: {}", finalTitle);

        for (Resource res : contents) {
            try {
                String html = new String(res.getData(), StandardCharsets.UTF_8);
                Document doc = Jsoup.parse(html);
                Element body = doc.body();

                handleImages(doc, book, res.getHref(), uploadedImagesCache);

                handleInlineNotes(body);

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
                                appendFooterLinks(previousPage, currentPage.getUrl(), previousBookmarkToken, previousPage.getUsedToken());
                            }

                            previousPage = currentPage;
                            previousBookmarkToken = bookmarkToken;
                        }
                        currentBuffer = new ArrayList<>();
                        currentLength = 0;
                        pageCounter++;
                    }
                }
            } catch (Exception e) {
                log.error("解析书籍失败", e);
            }
        }

        // 最后一页
        if (!currentBuffer.isEmpty()) {
            String pageTitle = finalTitle + " (" + pageCounter + ") - End";
            TelegraphService.PageResult lastPage = telegraphService.createPage(pageTitle, currentBuffer);
            if (lastPage != null) {
                pageUrls.add(lastPage.getUrl());
                if (previousPage != null) {
                    appendFooterLinks(previousPage, lastPage.getUrl(), previousBookmarkToken, previousPage.getUsedToken());
                }
                String lastToken = bookmarkService.createBookmarkToken(finalTitle, pageTitle, lastPage.getUrl());
                appendFooterLinks(lastPage, null, lastToken, lastPage.getUsedToken());
            }
        }

        return pageUrls;
    }

    /**
     * 解析相对路径，获取资源在 EPUB 中的绝对 href
     * 例如：当前在 Text/chapter1.html，引用 ../Images/1.jpg -> Images/1.jpg
     */
    private String resolveHref(String baseHref, String relativeHref) {
        try {
            relativeHref = java.net.URLDecoder.decode(relativeHref, StandardCharsets.UTF_8);

            if (baseHref == null || baseHref.isEmpty()) return relativeHref;

            java.net.URI baseUri = new java.net.URI("file:///" + baseHref);
            java.net.URI resolvedUri = baseUri.resolve(relativeHref);

            String path = resolvedUri.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        } catch (Exception e) {
            return relativeHref;
        }
    }

    private void handleImages(Document doc, Book book, String currentResourceHref, Map<String, String> cache) {
        Elements imgs = doc.select("img");
        if (imgs.isEmpty()) return;

        log.info("正在处理章节图片，共 {} 张", imgs.size());

        for (Element img : imgs) {
            String src = img.attr("src");
            if (src.isEmpty() || src.startsWith("http")) continue;

            try {
                String imageHref = resolveHref(currentResourceHref, src);

                if (cache.containsKey(imageHref)) {
                    img.attr("src", cache.get(imageHref));
                    continue;
                }

                Resource imageRes = book.getResources().getByHref(imageHref);
                if (imageRes == null) {
                    String fileName = new File(src).getName();
                    log.debug("未找到图片资源: {}", imageHref);
                    img.remove();
                    continue;
                }

                String contentType = imageRes.getMediaType() != null ? imageRes.getMediaType().toString() : "image/jpeg";
                String remoteUrl = telegraphService.uploadImage(imageRes.getData(), contentType);

                if (remoteUrl != null) {
                    cache.put(imageHref, remoteUrl);
                    img.attr("src", remoteUrl);
                    img.attr("style", "max-width: 100%;");
                } else {
                    img.remove();
                }

            } catch (Exception e) {
                log.warn("处理图片异常: {} - {}", src, e.getMessage());
                img.remove();
            }
        }
    }

    /**
     * 处理内联注脚：支持标准跳转注脚和多看/掌阅式属性注脚
     */
    private void handleInlineNotes(Element body) {
        Elements links = body.select("a");

        for (int i = links.size() - 1; i >= 0; i--) {
            Element link = links.get(i);
            String noteContent = null;

            Element img = link.selectFirst("img");
            if (img != null) {
                if (img.hasAttr("alt") && !img.attr("alt").trim().isEmpty()) {
                    String alt = img.attr("alt").trim();
                    if (alt.length() > 5 || containsChinese(alt)) {
                        noteContent = alt;
                    }
                }
                if (noteContent == null && img.hasAttr("zy-footnote")) {
                    noteContent = img.attr("zy-footnote").trim();
                }
            }

            if (noteContent == null && link.hasAttr("href")) {
                String href = link.attr("href");

                boolean looksLikeFootnote = href.startsWith("#") &&
                                            (link.hasClass("duokan-footnote") ||
                                             link.hasClass("epub-footnote") ||
                                             isNumericFootnote(link));

                if (looksLikeFootnote) {
                    try {
                        String targetId = href.substring(1);
                        Element targetElement = body.getElementById(targetId);
                        if (targetElement != null) {
                            noteContent = targetElement.text().trim();
                            removeFootnoteDefinition(targetElement);
                        }
                    } catch (Exception e) {
                        log.debug("查找注脚目标失败: {}", href);
                    }
                }
            }

            if (noteContent != null && !noteContent.isEmpty()) {
                String newText = "（注：" + noteContent + "）";
                TextNode textNode = new TextNode(newText);

                if (link.parent() != null && link.parent().tagName().equalsIgnoreCase("sup")) {
                    link.parent().replaceWith(textNode);
                } else {
                    link.replaceWith(textNode);
                }

                log.debug("已内联注脚: {}", truncate(noteContent, 20));
            }
        }
    }

    private boolean containsChinese(String str) {
        return str.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FA5);
    }

    private void removeFootnoteDefinition(Element targetElement) {
        if (targetElement.tagName().equalsIgnoreCase("li")) {
            targetElement.remove();
        } else if (targetElement.text().length() < 500) {
            targetElement.remove();
        }
    }

    private String truncate(String str, int len) {
        return str.length() > len ? str.substring(0, len) + "..." : str;
    }

    private boolean isNumericFootnote(Element link) {
        String text = link.text().trim().replace("[", "").replace("]", "");
        if (link.parent() != null && link.parent().tagName().equals("sup")) return true;
        return text.matches("\\d+");
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
                nextLink.put("children", List.of("👉 下一页  "));
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
            String text = telegraphService.cleanText(((TextNode) node).text()).trim();
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
