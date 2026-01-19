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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpubService {

    private final BookmarkService bookmarkService;
    private final R2StorageService r2StorageService;

    @Value("${app.chars-per-page:3000}")
    private int charsPerPage;

    @Value("${cloud.r2.public-domain:https://epub.8void.sbs}")
    private String apiBaseUrl;

    @Value("${telegram.bot.username}")
    private String botUsername;

    public List<String> processEpub(java.io.InputStream epubStream, String fileName) throws Exception {
        Book book = new EpubReader().readEpub(epubStream);

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
                handleImagesR2(doc, book, res.getHref(), bookId);

                for (Element child : body.children()) {
                    String childHtml = child.outerHtml();
                    int childLen = child.text().length();

                    if (!child.select("img").isEmpty() || child.tagName().equalsIgnoreCase("img") || child.tagName().equalsIgnoreCase("svg")) {
                        childLen += 1000;
                    }

                    int minPageThreshold = 800;
                    if ((currentLength + childLen > charsPerPage) && (currentLength > minPageThreshold)) {
                        String token = "bm_" + UUID.randomUUID().toString().substring(0, 8);
                        String pageUrl = uploadPage(bookId, bookTitle, pageCounter, currentHtmlBuffer.toString(), false, token);
                        pageUrls.add(pageUrl);
                        bookmarkService.createBookmarkToken(bookTitle, bookTitle + " (" + pageCounter + ")", pageUrl, token);
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
            String token = "bm_" + UUID.randomUUID().toString().substring(0, 8);
            String pageUrl = uploadPage(bookId, bookTitle, pageCounter, currentHtmlBuffer.toString(), true, token);
            pageUrls.add(pageUrl);
            bookmarkService.createBookmarkToken(bookTitle, bookTitle + " (" + pageCounter + ") - End", pageUrl, token);
        }

        return pageUrls;
    }

    private String uploadPage(String bookId, String bookTitle, int pageIndex, String content, boolean isLastPage, String token) {
        String html = buildHtmlTemplate(bookTitle, content, pageIndex, isLastPage, token);
        String path = "books/" + bookId + "/" + pageIndex + ".html";
        return r2StorageService.uploadFile(path, html.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    private void handleImagesR2(Document doc, Book book, String currentResourceHref, String bookId) {
        for (Element img : doc.select("img")) {
            String src = img.attr("src");
            if (src.startsWith("http") || src.contains("tgchannels")) continue;
            try {
                String imageHref = resolveHref(currentResourceHref, src);
                Resource imageRes = book.getResources().getByHref(imageHref);
                if (imageRes != null) {
                    byte[] data = imageRes.getData();
                    if (data.length > 100) {
                        String fileName = UUID.randomUUID().toString().substring(0, 8) + ".jpg"; // 简化后缀处理
                        String path = "books/" + bookId + "/images/" + fileName;

                        String r2Url = r2StorageService.uploadFile(path, data, "image/jpeg"); // 需根据实际类型设置 Content-Type

                        img.attr("src", r2Url); // 替换为 R2 的绝对路径
                        img.attr("style", "max-width: 100%; height: auto; display: block; margin: 10px auto;");
                    }
                }
            } catch (Exception e) {
                log.warn("Image error", e);
            }
        }
    }

    private String buildHtmlTemplate(String title, String content, int pageIndex, boolean isLastPage, String token) {
        // 计算上一页/下一页的相对路径
        String prevUrl = (pageIndex > 1) ? "./" + (pageIndex - 1) + ".html" : "javascript:void(0)";
        String nextUrl = (!isLastPage) ? "./" + (pageIndex + 1) + ".html" : "javascript:void(0)";

        // 按钮显示逻辑
        String prevStyle = (pageIndex == 1) ? "display: none;" : "display: inline-flex;";
        String nextStyle = (isLastPage) ? "display: none;" : "display: inline-flex;";
        String endStyle = (isLastPage) ? "display: inline;" : "display: none;";

        title = (title != null && title.length() > 10) ? title.substring(0, 10) + "..." : title;

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <title>%s</title>
                    <link rel="icon" href="data:image/svg+xml,<svg xmlns=%%22http://www.w3.org/2000/svg%%22 viewBox=%%220 0 100 100%%22><text y=%%22.9em%%22 font-size=%%2290%%22>📚</text></svg>">
                
                    <!-- 引入 Telegram Web App SDK -->
                    <script src="https://telegram.org/js/telegram-web-app.js"></script>
                
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                            line-height: 1.8;
                            margin: 0;
                            padding: 20px;
                            background-color: #FAF9DE;
                            color: #2c3e50;
                            padding-bottom: 100px;
                            transition: background-color 0.3s, color 0.3s;
                        }
                
                        @media (prefers-color-scheme: dark) {
                            body {
                                background-color: #1e1e1e;
                                color: #b0b0b0;
                            }
                        }
                
                        h2 {
                            margin-top: 0;
                            font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
                            color: #1a1a1a;
                            border-bottom: 1px solid rgba(0, 0, 0, 0.1);
                            padding-bottom: 15px;
                            margin-bottom: 20px;
                            font-weight: 600;
                            padding-right: 80px;
                        }
                
                        @media (prefers-color-scheme: dark) {
                            h2 {
                                color: #e0e0e0;
                                border-bottom-color: rgba(255, 255, 255, 0.1);
                            }
                        }
                
                        .content {
                            font-size: 19px;
                            text-align: justify;
                            margin-bottom: 30px;
                            max-width: 800px;
                            margin-left: auto;
                            margin-right: auto;
                            letter-spacing: 0.05em;
                        }
                
                        .font-style-serif {
                            font-family: "Songti SC", "SimSun", "STSong", "AR PL New Sung", "Georgia", "Times New Roman", serif;
                        }
                
                        .font-style-sans {
                            font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "SimHei", "Segoe UI", Roboto, sans-serif;
                            letter-spacing: 0.02em;
                        }
                
                        /* --- 图片样式 --- */
                        .content img {
                            max-width: 100%%;
                            height: auto;
                            border-radius: 8px;
                            margin: 15px auto;
                            display: block;
                            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                            opacity: 0;
                            transition: opacity 0.2s ease-in;
                            color: transparent;
                        }
                
                        .content img.loaded {
                            opacity: 1;
                        }
                
                        .content svg image {
                            opacity: 1;
                        }
                
                        /* --- 悬浮字体切换按钮 --- */
                        .font-switch-btn {
                            position: fixed;
                            top: 15px;
                            right: 15px;
                            background-color: rgba(255, 255, 255, 0.9);
                            border: 1px solid #d4d0b8;
                            color: #5d5d5d;
                            padding: 8px 14px;
                            border-radius: 20px;
                            font-size: 14px;
                            font-weight: bold;
                            cursor: pointer;
                            z-index: 100;
                            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
                            backdrop-filter: blur(5px);
                            transition: all 0.2s;
                            user-select: none;
                            -webkit-tap-highlight-color: transparent;
                        }
                
                        .font-switch-btn:active {
                            transform: scale(0.95);
                            background-color: #f0f0f0;
                        }
                
                        @media (prefers-color-scheme: dark) {
                            .font-switch-btn {
                                background-color: rgba(50, 50, 50, 0.9);
                                color: #ccc;
                                border-color: #444;
                            }
                        }
                
                        /* --- 底部导航栏 --- */
                        .nav-bar {
                            margin-top: 40px;
                            padding-top: 20px;
                            border-top: 1px solid rgba(0, 0, 0, 0.1);
                            max-width: 800px;
                            margin-left: auto;
                            margin-right: auto;
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            gap: 8px;
                            flex-wrap: wrap;
                        }
                
                        @media (prefers-color-scheme: dark) {
                            .nav-bar {
                                border-top-color: rgba(255, 255, 255, 0.1);
                            }
                        }
                
                        .btn {
                            text-decoration: none;
                            padding: 10px 16px;
                            border-radius: 12px;
                            font-size: 15px;
                            font-weight: 600;
                            border: none;
                            cursor: pointer;
                            display: inline-flex;
                            align-items: center;
                            justify-content: center;
                            transition: all 0.2s ease;
                            box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
                            -webkit-tap-highlight-color: transparent;
                            white-space: nowrap;
                            height: 42px;
                            box-sizing: border-box;
                        }
                
                        .btn:active {
                            transform: translateY(1px);
                            box-shadow: none;
                        }
                
                        .btn-primary {
                            background-color: #5b8cff;
                            color: #ffffff;
                        }
                
                        .btn-secondary {
                            background-color: #e8e4c9;
                            color: #5d5d5d;
                            border: 1px solid #d4d0b8;
                        }
                
                        @media (prefers-color-scheme: dark) {
                            .btn-secondary {
                                background-color: #333;
                                color: #ccc;
                                border-color: #555;
                            }
                        }
                
                        /* --- 页码输入框样式 --- */
                        .page-input {
                            width: 50px;
                            height: 42px;
                            padding: 0 5px;
                            border-radius: 12px;
                            border: 1px solid #d4d0b8;
                            background-color: #fff;
                            text-align: center;
                            font-size: 15px;
                            font-weight: 600;
                            color: #5d5d5d;
                            outline: none;
                            box-sizing: border-box;
                            transition: border-color 0.2s;
                            -moz-appearance: textfield;
                        }
                
                        .page-input::-webkit-outer-spin-button,
                        .page-input::-webkit-inner-spin-button {
                            -webkit-appearance: none;
                            margin: 0;
                        }
                
                        .page-input:focus {
                            border-color: #5b8cff;
                            box-shadow: 0 0 0 2px rgba(91, 140, 255, 0.2);
                        }
                
                        @media (prefers-color-scheme: dark) {
                            .page-input {
                                background-color: #333;
                                border-color: #555;
                                color: #ccc;
                            }
                        }
                        /* Toast 样式 (新增，用于书签提示) */
                        .toast {
                            position: fixed;
                            top: 20px;
                            left: 50%%;
                            transform: translateX(-50%%);
                            background: rgba(0,0,0,0.8);
                            color: white;
                            padding: 10px 20px;
                            border-radius: 20px;
                            display: none;
                            z-index: 9999;
                            font-size: 14px;
                        }
                    </style>
                
                    <script>
                        function checkImage(img) {
                            if (img.complete && img.naturalWidth > 0) {
                                img.classList.add('loaded');
                            }
                        }
                
                        window.addEventListener('load', function (e) {
                            if (e.target && e.target.tagName === 'IMG') {
                                e.target.classList.add('loaded');
                            }
                        }, true);
                
                        window.addEventListener('error', function (e) {
                            if (!e.target || !e.target.tagName) return;
                            const tag = e.target.tagName.toUpperCase();
                
                            if (tag === 'IMG') {
                                e.target.remove();
                            } else if (tag === 'IMAGE') {
                                const svgParent = e.target.closest('svg');
                                if (svgParent) svgParent.remove();
                                else e.target.remove();
                            }
                        }, true);
                
                        document.addEventListener('DOMContentLoaded', function () {
                            document.querySelectorAll('img').forEach(img => {
                                checkImage(img);
                                if (!img.complete) {
                                    img.onload = function () {
                                        this.classList.add('loaded');
                                    };
                                    img.onerror = function () {
                                        this.remove();
                                    };
                                } else if (img.naturalWidth === 0) {
                                    img.remove();
                                }
                            });
                        });
                    </script>
                </head>
                <body>
                
                <div id="toast" class="toast"></div>
                
                <div id="fontBtn" class="font-switch-btn" onclick="toggleFont()">
                    Aa 宋体
                </div>
                
                <h2 id="articleTitle" data-token="%s">%s</h2>
                <div id="contentArea" class="content font-style-serif">%s</div>
                
                <script>
                    (function () {
                        var imgs = document.getElementById('contentArea').getElementsByTagName('img');
                        for (var i = 0; i < imgs.length; i++) {
                            if (imgs[i].complete) {
                                imgs[i].classList.add('loaded');
                            }
                        }
                    })();
                </script>
                
                <div class="nav-bar">
                    <a id="prevBtn"
                       href="%s"
                       onclick="handlePrevPage(event)"
                       class="btn btn-secondary"
                       style="%s">
                        ←
                    </a>
                
                    <input type="number"
                           id="pageInput"
                           class="page-input"
                           placeholder="页"
                           value="%d"
                           onkeyup="handlePageInput(event)">
                
                    <button onclick="saveBookmark()" class="btn btn-secondary">
                        🔖 保存书签
                    </button>
                
                    <a id="nextBtn"
                       href="%s"
                       onclick="handleNextPage(event)"
                       class="btn btn-primary"
                       style="%s">
                        →
                    </a>
                
                    <span style="color: #999; font-size: 14px; %s">
                        完
                    </span>
                </div>
                
                <script>
                    const tg = window.Telegram.WebApp;
                    tg.ready();
                    tg.expand();
                
                    const contentArea = document.getElementById('contentArea');
                    const fontBtn = document.getElementById('fontBtn');
                    const titleElem = document.getElementById('articleTitle');
                    const nextBtn = document.getElementById('nextBtn');
                    const prevBtn = document.getElementById('prevBtn');
                    const pageInput = document.getElementById('pageInput');
                
                    // 注入的变量
                    let bookmarkToken = "%s";
                    let nextPageUrl = "%s";
                    let botUsername = "%s";
                
                    // 静态页面需要的额外变量
                    const API_BASE = "%s";
                    const BOOK_NAME = "%s";
                    const PAGE_INDEX = %d;
                    const CURRENT_URL = window.location.href;
                
                    let preloadedDoc = null;
                
                    const FONT_SERIF = 'font-style-serif';
                    const FONT_SANS = 'font-style-sans';
                    const STORAGE_KEY = 'epub_reader_font_style';
                
                    function updateNavState() {
                        // 静态页面不需要复杂的正则匹配，直接读取 input 的值即可
                        // 但为了保持逻辑一致，我们还是从 URL 尝试解析
                        // 假设 URL 是 .../1.html
                        const path = window.location.pathname;
                        const match = path.match(/(\\d+)\\.html$/);
                
                        if (match) {
                            const pageIndex = parseInt(match[1]);
                            pageInput.value = pageIndex;
                        }
                    }
                
                    function handlePageInput(e) {
                        if (e.key === 'Enter') {
                            const targetPage = parseInt(pageInput.value);
                            if (isNaN(targetPage) || targetPage < 1) {
                                showMessage("请输入有效的页码");
                                return;
                            }
                            pageInput.blur();
                            jumpToPage(targetPage);
                        }
                    }
                
                    function jumpToPage(pageIndex) {
                        // 静态文件跳转逻辑：直接替换 URL 中的数字部分
                        // 例如： ./1.html -> ./5.html
                        const targetUrl = "./" + pageIndex + ".html";
                
                        // 如果是当前页，不跳转
                        if (pageIndex === PAGE_INDEX) return;
                
                        fetch(targetUrl)
                            .then(res => {
                                if (!res.ok) throw new Error("Page not found");
                                return res.text();
                            })
                            .then(html => {
                                const parser = new DOMParser();
                                const doc = parser.parseFromString(html, 'text/html');
                
                                if (!doc.getElementById('contentArea')) {
                                    throw new Error("Invalid page content");
                                }
                
                                window.history.pushState({}, '', targetUrl);
                                swapContent(doc);
                            })
                            .catch(err => {
                                console.warn("Jump failed:", err);
                                showMessage("❌ 页码不存在");
                            });
                    }
                
                    function prefetchNext() {
                        // 静态页面 nextUrl 已经是 ./x.html 格式
                        if (!nextPageUrl || nextPageUrl === "javascript:void(0)") return;
                        fetch(nextPageUrl)
                            .then(response => response.text())
                            .then(html => {
                                const parser = new DOMParser();
                                preloadedDoc = parser.parseFromString(html, 'text/html');
                            })
                            .catch(err => console.error('预加载失败', err));
                    }
                
                    function handleNextPage(e) {
                        if (!preloadedDoc) return; // 如果没预加载好，就让它自然跳转 href
                        e.preventDefault();
                        if (tg.HapticFeedback) tg.HapticFeedback.selectionChanged();
                        window.history.pushState({}, '', nextPageUrl);
                        swapContent(preloadedDoc);
                    }
                
                    function handlePrevPage(e) {
                        const url = prevBtn.getAttribute('href');
                        if (!url || url === "javascript:void(0)") return;
                        e.preventDefault();
                        if (tg.HapticFeedback) tg.HapticFeedback.selectionChanged();
                        window.history.pushState({}, '', url);
                        fetch(url)
                            .then(res => res.text())
                            .then(html => {
                                const parser = new DOMParser();
                                const doc = parser.parseFromString(html, 'text/html');
                                swapContent(doc);
                            })
                            .catch(err => {
                                console.error('加载上一页失败', err);
                                window.location.href = url; // 降级处理：直接跳转
                            });
                    }
                
                    function swapContent(doc) {
                        const newTitleElem = doc.getElementById('articleTitle');
                        if (newTitleElem) {
                            titleElem.innerText = newTitleElem.innerText;
                            document.title = newTitleElem.innerText;
                            bookmarkToken = newTitleElem.getAttribute('data-token');
                            titleElem.setAttribute('data-token', bookmarkToken);
                        }
                
                        const newContent = doc.getElementById('contentArea').innerHTML;
                        contentArea.innerHTML = newContent;
                        window.scrollTo(0, 0);
                
                        const newNextBtn = doc.getElementById('nextBtn');
                        const newPrevBtn = doc.getElementById('prevBtn');
                
                        // 更新下一页按钮
                        if (newNextBtn) {
                            nextPageUrl = newNextBtn.getAttribute('href');
                            nextBtn.setAttribute('href', nextPageUrl);
                            nextBtn.style.display = newNextBtn.style.display;
                            // 处理 "完" 的显示
                            const endSpan = document.querySelector('.nav-bar span');
                            if (endSpan) endSpan.style.display = (nextPageUrl.includes('javascript')) ? 'inline' : 'none';
                        }
                
                        // 更新上一页按钮
                        if (newPrevBtn) {
                            prevBtn.setAttribute('href', newPrevBtn.getAttribute('href'));
                            prevBtn.style.display = newPrevBtn.style.display;
                        }
                
                        // 更新输入框
                        const newPageInput = doc.getElementById('pageInput');
                        if (newPageInput) {
                            pageInput.value = newPageInput.value;
                        }
                
                        initImages();
                        // updateNavState(); // 静态页面不需要重新解析 URL，因为 input value 已经从新页面获取了
                        preloadedDoc = null;
                        prefetchNext();
                    }
                
                    window.addEventListener('popstate', () => {
                        window.location.reload();
                    });
                
                    function initImages() {
                        const imgs = contentArea.getElementsByTagName('img');
                        for (let i = 0; i < imgs.length; i++) {
                            const img = imgs[i];
                            img.onerror = function () {
                                this.remove();
                            };
                            img.onload = function () {
                                this.classList.add('loaded');
                            };
                            if (img.complete && img.naturalWidth > 0) img.classList.add('loaded');
                        }
                    }
                
                    function initFont() {
                        const savedFont = localStorage.getItem(STORAGE_KEY);
                        contentArea.classList.remove(FONT_SERIF, FONT_SANS);
                        if (savedFont) {
                            contentArea.classList.add(savedFont);
                            updateBtnText(savedFont);
                        } else {
                            contentArea.classList.add(FONT_SERIF);
                            updateBtnText(FONT_SERIF);
                        }
                    }
                
                    function toggleFont() {
                        if (tg.HapticFeedback) tg.HapticFeedback.selectionChanged();
                        const isSerif = contentArea.classList.contains(FONT_SERIF);
                        const newFont = isSerif ? FONT_SANS : FONT_SERIF;
                        contentArea.classList.remove(FONT_SERIF, FONT_SANS);
                        contentArea.classList.add(newFont);
                        localStorage.setItem(STORAGE_KEY, newFont);
                        updateBtnText(newFont);
                    }
                
                    function updateBtnText(fontClass) {
                        fontBtn.textContent = (fontClass === FONT_SERIF) ? "Aa 宋体" : "Aa 黑体";
                    }
                
                    function showMessage(msg) {
                        const t = document.getElementById('toast');
                        t.innerText = msg;
                        t.style.display = 'block';
                        setTimeout(() => t.style.display = 'none', 2000);
                    }
                
                    function saveBookmark() {
                        const user = tg.initDataUnsafe && tg.initDataUnsafe.user;
                
                        // 混合架构适配：如果不在 Telegram 内，尝试 Deep Link 跳转
                        if (!user) {
                            if (botUsername && bookmarkToken) {
                                window.location.href = `https://t.me/${botUsername}?start=${bookmarkToken}`;
                            } else {
                                showMessage("请在 Telegram 中打开");
                            }
                            return;
                        }
                
                        // 混合架构适配：使用 Direct URL 模式
                        fetch(API_BASE + '/api/miniapp/bookmark', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({
                                userId: user.id,
                                token: "DIRECT_URL_SAVE",
                                directData: {
                                    url: window.location.href,
                                    bookName: BOOK_NAME,
                                    chapterTitle: "第 " + pageInput.value + " 页"
                                }
                            })
                        })
                        .then(res => res.json())
                        .then(data => {
                            if (data.success) showMessage('✅ 书签已保存');
                            else showMessage('❌ 保存失败: ' + (data.error || '未知错误'));
                        })
                        .catch(() => showMessage('❌ 网络错误'));
                    }
                
                    initFont();
                    // updateNavState();
                    setTimeout(prefetchNext, 800);
                
                </script>
                </body>
                </html>
                """.formatted(
                title, // <title>
                token, title, // <h2 data-token>
                content, // contentArea
                prevUrl, prevStyle, // Prev Button
                pageIndex, // Input value
                nextUrl, nextStyle, // Next Button
                endStyle, // End Span
                token, nextUrl, botUsername, // JS Variables 1
                apiBaseUrl, title, pageIndex // JS Variables 2
        );
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