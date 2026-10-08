package com.campusforum.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.infra.StorageService;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.infra.security.SafeHttpClient;
import com.campusforum.learning.domain.LearningLesson;
import com.campusforum.learning.domain.LearningTutorial;
import com.campusforum.learning.mapper.LearningLessonMapper;
import com.campusforum.learning.mapper.LearningTutorialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 公开免费教程内容爬取同步服务。
 *
 * <p>仅抓取公开公益内容（如菜鸟教程），每篇存储来源与原文链接。
 * 阅读页显著标注「内容来自 XX」并回链原文，符合非盈利公益使用原则。
 * 抓取限速 800ms/页，UA 礼貌标识。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningCrawlerService {

    private static final String UA = "QingyunLearningCrawler/1.0 (non-profit educational use; +https://qingyunsq.top/learning)";
    private static final int CONNECT_MS = 8_000;
    private static final int READ_MS = 15_000;
    private static final long DELAY_MS = 800;

    private final LearningTutorialMapper tutorialMapper;
    private final LearningLessonMapper lessonMapper;
    private final HtmlSanitizerService sanitizer;
    private final StorageService storageService;

    @Transactional
    public Map<String, Object> crawlRunoob(String slug, String title, String indexUrl,
                                            String category, String icon, String description) {
        RestTemplate rt = SafeHttpClient.build(CONNECT_MS, READ_MS);
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", UA);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        log.info("⌛ Crawling tutorial: {} ({})", title, slug);
        long start = System.currentTimeMillis();

        // Step 1: Fetch index page
        ResponseEntity<String> resp;
        try {
            resp = rt.exchange(indexUrl, HttpMethod.GET, req, String.class);
        } catch (Exception e) {
            log.error("Failed to fetch index: {}", indexUrl, e);
            throw new RuntimeException("无法访问教程首页: " + e.getMessage());
        }
        String indexHtml = resp.getBody();
        if (indexHtml == null || indexHtml.isBlank()) {
            throw new RuntimeException("教程首页返回空内容");
        }
        Document doc = Jsoup.parse(indexHtml, indexUrl);

        // Step 2: Extract chapter links from left sidebar (#leftcolumn a)
        Elements links = doc.select("#leftcolumn a");
        if (links.isEmpty()) {
            links = doc.select(".left-column a, .sidebar a, .design a"); // fallback selectors
        }
        List<String[]> chapters = new ArrayList<>();
        for (Element a : links) {
            String href = a.absUrl("href");
            String text = a.text().trim();
            if (text.isEmpty() || text.equals("首页") || text.contains("广告")) continue;
            if (href.isEmpty() || !href.startsWith("http")) continue;
            chapters.add(new String[]{text, href});
        }
        log.info("Found {} chapter links for {}", chapters.size(), slug);

        // Step 3: Upsert tutorial
        LearningTutorial tut = tutorialMapper.selectOne(
                new LambdaQueryWrapper<LearningTutorial>().eq(LearningTutorial::getSlug, slug));
        if (tut == null) {
            tut = new LearningTutorial();
            tut.setId("tut_" + shortId());
            tut.setSlug(slug);
            tut.setSource("菜鸟教程");
            tut.setSourceUrl(indexUrl);
            tut.setCategory(category);
            tut.setIcon(icon);
            tut.setSortOrder(0);
        }
        boolean isNew = tut.getCreatedAt() == null;
        tut.setTitle(title);
        tut.setDescription(description);
        tut.setLessonCount(0);
        if (isNew) {
            tutorialMapper.insert(tut);
        } else {
            tutorialMapper.updateById(tut);
        }

        // Clear old lessons on re-crawl
        lessonMapper.delete(new LambdaQueryWrapper<LearningLesson>().eq(LearningLesson::getTutorialId, tut.getId()));

        // Step 4: Crawl each chapter
        int saved = 0;
        for (int i = 0; i < chapters.size(); i++) {
            String[] ch = chapters.get(i);
            String chTitle = ch[0];
            String chUrl = ch[1];
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

            String body;
            try {
                ResponseEntity<String> r2 = rt.exchange(chUrl, HttpMethod.GET, req, String.class);
                body = r2.getBody();
            } catch (Exception e) {
                log.warn("Failed to fetch chapter {}: {}", chUrl, e.getMessage());
                continue;
            }
            if (body == null || body.isBlank()) continue;

            Document chDoc = Jsoup.parse(body, chUrl);

            // Extract main content area
            Element content = chDoc.selectFirst("#content");
            if (content == null) {
                content = chDoc.selectFirst(".article, .main-content, article, .content");
            }
            if (content == null) {
                log.warn("No content found for: {}", chUrl);
                continue;
            }

            // Remove noisy elements
            content.select("script, style, .ad, .adsbygoogle, .share, .related, .nav, .sidebar, ins, iframe, noscript").remove();

            String html = content.html();
            if (html.length() < 50) continue; // too short -> skip

            // Sanitize
            String clean = sanitizer.sanitizePost(html);

            // Write content to OSS instead of MySQL MEDIUMTEXT
            String ossKey = null;
            try {
                String ossName = slug + "/" + String.format("%03d", i) + ".html";
                ossKey = storageService.upload(
                        new java.io.ByteArrayInputStream(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        ossName, "text/html; charset=utf-8", clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            } catch (Exception e) {
                log.warn("OSS upload failed for chapter {}, falling back to DB: {}", chUrl, e.getMessage());
            }

            LearningLesson lesson = new LearningLesson();
            lesson.setId("les_" + shortId());
            lesson.setTutorialId(tut.getId());
            lesson.setTitle(chTitle);
            lesson.setContentOssKey(ossKey);
            lesson.setContent(ossKey == null ? clean : null); // fallback to DB if OSS failed
            lesson.setSourceUrl(chUrl);
            lesson.setOrderIndex(i);
            lessonMapper.insert(lesson);
            saved++;
            log.debug("  ✅ [{}] {} — {} chars, oss={}", i + 1, chTitle, clean.length(), ossKey != null);
        }

        tut.setLessonCount(saved);
        tutorialMapper.updateById(tut);

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("✅ Crawled {} ({}): {} chapters saved in {}s", title, slug, saved, elapsed);
        return Map.of("slug", slug, "title", title, "lessons", saved, "elapsedSeconds", elapsed);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
