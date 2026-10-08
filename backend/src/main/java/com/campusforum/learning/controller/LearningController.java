package com.campusforum.learning.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.ai.workspace.AiWorkspaceService;
import com.campusforum.common.R;
import com.campusforum.infra.StorageService;
import com.campusforum.learning.domain.LearningLesson;
import com.campusforum.learning.domain.LearningTutorial;
import com.campusforum.learning.mapper.LearningLessonMapper;
import com.campusforum.learning.mapper.LearningTutorialMapper;
import com.campusforum.learning.service.LearningCrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LearningController {

    private final LearningTutorialMapper tutorialMapper;
    private final LearningLessonMapper lessonMapper;
    private final LearningCrawlerService crawlerService;
    private final StorageService storageService;
    private final AiWorkspaceService aiWorkspaceService;

    // ---- Public read endpoints ----

    @GetMapping("/learning/tutorials")
    public R<List<Map<String, Object>>> listTutorials() {
        List<LearningTutorial> list = tutorialMapper.selectList(
                new LambdaQueryWrapper<LearningTutorial>().orderByAsc(LearningTutorial::getSortOrder));
        List<Map<String, Object>> result = new ArrayList<>();
        for (LearningTutorial t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("slug", t.getSlug());
            m.put("description", t.getDescription());
            m.put("source", t.getSource());
            m.put("sourceUrl", t.getSourceUrl());
            m.put("category", t.getCategory());
            m.put("icon", t.getIcon());
            m.put("lessonCount", t.getLessonCount());
            result.add(m);
        }
        return R.ok(result);
    }

    @GetMapping("/learning/tutorials/{id}")
    public R<Map<String, Object>> getTutorial(@PathVariable String id) {
        LearningTutorial t = tutorialMapper.selectById(id);
        if (t == null) return R.fail(40400, "教程不存在");
        List<LearningLesson> lessons = lessonMapper.selectList(
                new LambdaQueryWrapper<LearningLesson>()
                        .eq(LearningLesson::getTutorialId, id)
                        .orderByAsc(LearningLesson::getOrderIndex));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("slug", t.getSlug());
        m.put("description", t.getDescription());
        m.put("source", t.getSource());
        m.put("sourceUrl", t.getSourceUrl());
        m.put("category", t.getCategory());
        m.put("icon", t.getIcon());
        m.put("lessonCount", t.getLessonCount());
        List<Map<String, Object>> chapterList = new ArrayList<>();
        for (LearningLesson l : lessons) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("id", l.getId());
            ch.put("title", l.getTitle());
            ch.put("sourceUrl", l.getSourceUrl());
            ch.put("orderIndex", l.getOrderIndex());
            chapterList.add(ch);
        }
        m.put("chapters", chapterList);
        return R.ok(m);
    }

    @GetMapping("/learning/lessons/{id}")
    public R<Map<String, Object>> getLesson(@PathVariable String id) {
        LearningLesson l = lessonMapper.selectById(id);
        if (l == null) return R.fail(40400, "章节不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("tutorialId", l.getTutorialId());
        m.put("title", l.getTitle());
        m.put("content", resolveContent(l));
        m.put("sourceUrl", l.getSourceUrl());
        m.put("orderIndex", l.getOrderIndex());
        return R.ok(m);
    }

    /** 优先从 OSS 读取内容，OSS key 为空时回退到 DB content 列。 */
    private String resolveContent(LearningLesson l) {
        if (l.getContentOssKey() != null && !l.getContentOssKey().isBlank()) {
            try {
                java.io.InputStream is = storageService.download(l.getContentOssKey());
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("OSS read failed for lesson {}, falling back to DB: {}", l.getId(), e.getMessage());
            }
        }
        return l.getContent();
    }

    // ---- Admin reorder endpoints ----
    // 教程用 sort_order ASC（0..N-1，数值小靠前），笔记用 sort_order DESC（N..1，数值大靠前）
    // 两者语义不同是历史原因：教程原有字段是升序索引，笔记新加字段用"权重"直觉。

    @PutMapping("/admin/learning/tutorials/reorder")
    @SaCheckPermission("tenant:learning:manage")
    public R<Map<String, Object>> reorderTutorials(@RequestBody Map<String, Object> body) {
        List<String> ids = ids(body);
        if (ids.isEmpty()) return R.fail(40000, "ids 不能为空");
        int updated = 0;
        for (int i = 0; i < ids.size(); i++) {
            LearningTutorial t = tutorialMapper.selectById(ids.get(i));
            if (t == null) continue;
            t.setSortOrder(i);
            tutorialMapper.updateById(t);
            updated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("total", ids.size());
        return R.ok(out);
    }

    @PutMapping("/admin/learning/notes/reorder")
    @SaCheckPermission("tenant:learning:manage")
    public R<Map<String, Object>> reorderNotes(@RequestBody Map<String, Object> body) {
        List<String> ids = ids(body);
        if (ids.isEmpty()) return R.fail(40000, "ids 不能为空");
        int updated = aiWorkspaceService.adminReorderNotes(ids);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("total", ids.size());
        return R.ok(out);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null && !o.toString().isBlank()) out.add(o.toString());
        }
        return out;
    }

    // ---- Admin crawl endpoint ----

    @PostMapping("/admin/learning/crawl")
    @SaCheckPermission("tenant:dashboard")
    public R<Map<String, Object>> crawl(@RequestBody Map<String, Object> body) {
        String slug = str(body, "slug");
        String title = str(body, "title");
        String indexUrl = str(body, "indexUrl");
        String category = str(body, "category");
        String icon = str(body, "icon");
        String description = str(body, "description");
        if (slug.isEmpty() || title.isEmpty() || indexUrl.isEmpty()) {
            return R.fail(40000, "slug/title/indexUrl 不能为空");
        }
        return R.ok(crawlerService.crawlRunoob(slug, title, indexUrl, category, icon, description));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
