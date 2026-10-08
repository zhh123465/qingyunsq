package com.campusforum.ai.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.admin.dto.SyncExternalRequest;
import com.campusforum.ai.workspace.domain.AiNote;
import com.campusforum.ai.workspace.mapper.AiNoteMapper;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部笔记同步服务：把公开的第三方笔记（VitePress 静态站 / 博客园 / 单页）
 * 抓取正文并转换为 Markdown 落入 {@code ai_notes}，保留出处元数据以供学习页
 * 卡片外链角标与详情页出处 banner 展示。
 *
 * <p>与 {@link com.campusforum.infra.security.SafeHttpClient} 分开：后者是站内通用出站的
 * SSRF 白名单防线，与本服务的"允许指定外部博客域名"语义不匹配。本服务用独立的
 * {@code java.net.http.HttpClient}（可选代理），并在应用层维护 {@code allowedHosts} 白名单
 * 严格约束目标域名，防止被滥用为通用 SSRF 网关。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteSyncService {

    private final AiNoteMapper noteMapper;
    private final StorageService storageService;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${note-sync.proxy.host:}")
    private String proxyHost;

    @Value("${note-sync.proxy.port:0}")
    private int proxyPort;

    @Value("${note-sync.timeout-ms:15000}")
    private int timeoutMs;

    @Value("${note-sync.max-pages:50}")
    private int maxPages;

    @Value("${note-sync.allowed-hosts:blog-wheat-one-34.vercel.app,www.cnblogs.com}")
    private String allowedHostsRaw;

    private Set<String> allowedHosts;
    private HttpClient http;
    private FlexmarkHtmlConverter converter;

    @PostConstruct
    void init() {
        this.allowedHosts = new HashSet<>();
        for (String h : allowedHostsRaw.split(",")) {
            String t = h.trim();
            if (!t.isEmpty()) allowedHosts.add(t.toLowerCase());
        }
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            b.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            log.info("NoteSync HTTP client: proxy={}:{}, timeoutMs={}, allowedHosts={}",
                    proxyHost, proxyPort, timeoutMs, allowedHosts);
        } else {
            log.info("NoteSync HTTP client: no proxy, timeoutMs={}, allowedHosts={}",
                    timeoutMs, allowedHosts);
        }
        this.http = b.build();
        MutableDataSet opts = new MutableDataSet();
        this.converter = FlexmarkHtmlConverter.builder(opts).build();
    }

    /** 主入口：按 source 类型分派。 */
    public Map<String, Object> sync(SyncExternalRequest req) {
        String src = req.getSource();
        assertHostAllowed(req.getRootUrl());
        return switch (src) {
            case "single"    -> syncSingle(req);
            case "vitepress" -> syncVitePress(req);
            case "cnblogs"   -> syncCnblogs(req);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "unknown source: " + src);
        };
    }

    // ============================== 三种模式 ==============================

    private Map<String, Object> syncSingle(SyncExternalRequest req) {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        try {
            upsertOneUrl(req.getRootUrl(), req, ids);
        } catch (Exception e) {
            failed.add(failure(req.getRootUrl(), e));
        }
        return result(ids, failed);
    }

    /**
     * VitePress 目录页 → 抽出所有子页 URL → 逐一同步。
     * 目录页里的 &lt;a href="./xxx.html"&gt; 表示该目录下的子笔记。
     */
    private Map<String, Object> syncVitePress(SyncExternalRequest req) {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        // BFS：从 rootUrl 开始，递归时只允许 recursive=true
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(req.getRootUrl());
        int budget = maxPages;
        boolean recursive = Boolean.TRUE.equals(req.getRecursive());

        while (!queue.isEmpty() && budget > 0) {
            String url = queue.pollFirst();
            if (!visited.add(url)) continue;
            String html;
            try {
                html = fetch(url);
            } catch (Exception e) {
                failed.add(failure(url, e));
                continue;
            }
            Document doc = Jsoup.parse(html, url);
            Element root = doc.selectFirst("main .vp-doc, .VPDoc .vp-doc, .vp-doc");
            if (root == null) {
                // 无 vp-doc：跳过（可能是目录性索引页却无内容，或站点结构差异）
                log.warn("NoteSync vitepress: no vp-doc found at {}", url);
                continue;
            }
            // 目录页往往正文很短且主要是子文章链接。判断标准：如果本页只有一堆 <li><a>，则视为目录、不入库。
            String textContent = root.text();
            Elements childLinks = root.select("a[href$=.html]");
            boolean isIndexPage = textContent.length() < 400 && childLinks.size() >= 3;

            if (!isIndexPage) {
                try {
                    String md = htmlToMarkdown(root, url);
                    String title = pickTitle(doc, "vitepress");
                    upsertNoteRecord(url, title, md, req, ids);
                    budget--;
                } catch (Exception e) {
                    failed.add(failure(url, e));
                }
            }

            // 采集目录里的子页 URL（相对链接已被 Jsoup 通过 baseUri 解析成绝对）
            if (recursive || url.equals(req.getRootUrl())) {
                for (Element a : childLinks) {
                    String abs = a.attr("abs:href");
                    if (abs == null || abs.isBlank()) continue;
                    if (!isHostAllowed(abs)) continue;
                    // 只入队同源
                    if (!sameOrigin(abs, req.getRootUrl())) continue;
                    if (!visited.contains(abs)) queue.addLast(abs);
                }
            }
        }
        return result(ids, failed);
    }

    /**
     * 博客园：抓用户主页 → 遍历分页 (default.html?page=N) → 逐篇抓正文。
     */
    private Map<String, Object> syncCnblogs(SyncExternalRequest req) {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        String home = req.getRootUrl().replaceAll("/+$", "");

        Set<String> postUrls = new LinkedHashSet<>();
        int page = 1;
        int emptyRounds = 0;
        while (page <= 20 && emptyRounds < 2) {
            String pageUrl = home + "/default.html?page=" + page;
            String html;
            try {
                html = fetch(pageUrl);
            } catch (Exception e) {
                log.warn("cnblogs list page fetch failed p={}: {}", page, e.getMessage());
                break;
            }
            Document doc = Jsoup.parse(html, pageUrl);
            Elements links = doc.select(".postTitle a, .postTitle2, a.postTitle2");
            int before = postUrls.size();
            for (Element a : links) {
                String abs = a.attr("abs:href");
                if (abs != null && !abs.isBlank() && abs.contains("cnblogs.com")) {
                    postUrls.add(abs);
                }
            }
            if (postUrls.size() == before) emptyRounds++;
            else emptyRounds = 0;
            page++;
            if (postUrls.size() >= maxPages) break;
        }
        if (postUrls.size() > maxPages) {
            log.warn("cnblogs post count {} exceeds maxPages {}, truncating",
                    postUrls.size(), maxPages);
        }
        int budget = maxPages;
        for (String url : postUrls) {
            if (budget-- <= 0) break;
            try {
                upsertOneUrl(url, req, ids);
            } catch (Exception e) {
                failed.add(failure(url, e));
            }
        }
        return result(ids, failed);
    }

    // ============================== 抓取 + 转换 ==============================

    private void upsertOneUrl(String url, SyncExternalRequest req, List<String> ids) throws Exception {
        assertHostAllowed(url);
        String html = fetch(url);
        Document doc = Jsoup.parse(html, url);
        String source = req.getSource();
        Element root;
        if ("cnblogs".equals(source) || url.contains("cnblogs.com")) {
            root = doc.selectFirst("#cnblogs_post_body, #post_detail, article");
        } else {
            root = doc.selectFirst("main .vp-doc, .VPDoc .vp-doc, .vp-doc, article, main");
        }
        if (root == null) {
            throw new IllegalStateException("no content root selector matched");
        }
        String md = htmlToMarkdown(root, url);
        String title = pickTitle(doc, source);
        upsertNoteRecord(url, title, md, req, ids);
    }

    private String htmlToMarkdown(Element root, String baseUri) {
        // Jsoup 会通过 baseUri 让 abs:href / abs:src 生效；这里手动 flatten 所有 a/img 的相对路径。
        for (Element a : root.select("a[href]")) {
            String abs = a.attr("abs:href");
            if (!abs.isBlank()) a.attr("href", abs);
        }
        for (Element img : root.select("img[src]")) {
            String abs = img.attr("abs:src");
            if (!abs.isBlank()) img.attr("src", abs);
        }
        // 移除脚本、样式、评论组件等噪声
        root.select("script, style, .cnblogs_code_toolbar, .code-copy, .header-anchor").remove();
        String bodyHtml = root.outerHtml();
        return converter.convert(bodyHtml).trim();
    }

    private String pickTitle(Document doc, String source) {
        String t = "";
        if ("cnblogs".equals(source)) {
            // cnblogs 文章页的 <title> 格式："文章标题 - 博主名 - 博客园"
            // 先尝试正文标题元素，再退回 <title> 首段切分
            Element h1 = doc.selectFirst("#cb_post_title_url, h1.postTitle, .postTitle > a");
            if (h1 != null) t = h1.text().trim();
            if (t.isBlank()) {
                String pageTitle = doc.title();
                if (pageTitle != null && pageTitle.contains(" - ")) {
                    t = pageTitle.split(" - ", 2)[0].trim();
                } else if (pageTitle != null) {
                    t = pageTitle.trim();
                }
            }
        } else {
            // VitePress / 单页：直接抽正文 h1
            Element h1 = doc.selectFirst("main .vp-doc h1, .VPDoc h1, main h1, h1");
            if (h1 != null) t = h1.text().trim();
            if (t.isBlank()) {
                String title = doc.title();
                // 去掉常见站名后缀 " | 学习笔记" / " - 博客园"
                t = title == null ? "" :
                        title.replaceAll("\\s*[|\\-–]\\s*[^|\\-–]*$", "").trim();
                if (t.isBlank() && title != null) t = title;
            }
        }
        // 去锚点前缀符（#、¶）
        t = t.replaceAll("^[#¶\\s]+", "").trim();
        if (t.length() > 200) t = t.substring(0, 200);
        return t.isBlank() ? "未命名同步笔记" : t;
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent",
                        "Mozilla/5.0 (compatible; CampusForumNoteSync/1.0; +https://campus-forum)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    // ============================== 落库 ==============================

    @Transactional
    protected void upsertNoteRecord(String url, String title, String markdown,
                                    SyncExternalRequest req, List<String> ids) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("empty markdown after conversion");
        }
        String id = determineNoteId(url);
        AiNote existed = noteMapper.selectById(id);
        boolean isNew = existed == null;
        AiNote note = isNew ? new AiNote() : existed;
        if (isNew) {
            note.setId(id);
            note.setTenantId(1L);
            note.setViewCount(0L);
            note.setDeleted(0);
            note.setCreatedAt(LocalDateTime.now());
        }
        note.setTitle(title);
        note.setContentType("markdown");
        note.setStatus("published");
        note.setOwnerId(req.getOwnerId() != null ? req.getOwnerId() : 1L);
        note.setSourceUrl(url);
        note.setSourceName(req.getSourceName());
        note.setSourceAuthor(req.getSourceAuthor());
        note.setTags(serializeTags(req.getTags()));
        note.setUpdatedAt(LocalDateTime.now());

        // 大内容走 OSS；小则直接进 content 字段
        boolean useOss = markdown.length() > 100_000;
        if (useOss) {
            String ossKey = uploadOss(id, markdown);
            if (ossKey != null) {
                note.setContent(null);
                note.setContentOssKey(ossKey);
            } else {
                note.setContent(markdown);
                note.setContentOssKey(null);
            }
        } else {
            note.setContent(markdown);
            note.setContentOssKey(null);
        }

        if (isNew) noteMapper.insert(note);
        else noteMapper.updateById(note);
        ids.add(id);
    }

    private String uploadOss(String noteId, String content) {
        try {
            String ossName = "notes/" + noteId + ".md";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return storageService.upload(new ByteArrayInputStream(bytes),
                    ossName, "text/markdown; charset=utf-8", bytes.length);
        } catch (Exception e) {
            log.warn("Failed to upload synced note {} to OSS: {}", noteId, e.getMessage());
            return null;
        }
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        try {
            return JSON.writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ============================== 工具 ==============================

    /**
     * 幂等 ID：ns_ + sha256(url)[0..14] hex（共 31 字符 < VARCHAR(32) 上限）。
     * 同一 URL 再同步会命中同一行走 update；前缀 ns_ 让运维查库时能快速识别同步来源。
     */
    private String determineNoteId(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ns_");
            for (int i = 0; i < 14 && i < h.length; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能：SHA-256 是标准算法
            return ("ns_" + Integer.toHexString(url.hashCode()));
        }
    }

    private void assertHostAllowed(String url) {
        if (!isHostAllowed(url)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "host not in allowed-hosts whitelist: " + url);
        }
    }

    private boolean isHostAllowed(String url) {
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            return h != null && allowedHosts.contains(h.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameOrigin(String a, String b) {
        try {
            URI ua = URI.create(a), ub = URI.create(b);
            return ua.getHost() != null && ua.getHost().equalsIgnoreCase(ub.getHost());
        } catch (Exception e) { return false; }
    }

    private Map<String, Object> failure(String url, Exception e) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("url", url);
        f.put("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return f;
    }

    private Map<String, Object> result(List<String> ids, List<Map<String, Object>> failed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synced", ids.size());
        r.put("failed", failed);
        r.put("noteIds", ids);
        return r;
    }
}
