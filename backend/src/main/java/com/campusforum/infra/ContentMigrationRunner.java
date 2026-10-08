package com.campusforum.infra;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.ai.workspace.domain.AiNote;
import com.campusforum.ai.workspace.mapper.AiNoteMapper;
import com.campusforum.learning.domain.LearningLesson;
import com.campusforum.learning.mapper.LearningLessonMapper;
import com.campusforum.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 启动时将 MySQL MEDIUMTEXT 中的教程章节和笔记内容迁移到 OSS。
 * 仅处理 content_oss_key 为 NULL 且 content 非空的记录。
 * 幂等：重复运行不会重复迁移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentMigrationRunner implements ApplicationRunner {

    private final LearningLessonMapper lessonMapper;
    private final AiNoteMapper noteMapper;
    private final StorageService storageService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== ContentMigrationRunner: checking for content to migrate to OSS ===");
        migrateLessons();
        migrateNotes();
        log.info("=== ContentMigrationRunner: migration check complete ===");
    }

    private void migrateLessons() {
        TenantContext.setTenantId(1L);
        List<LearningLesson> lessons = lessonMapper.selectList(
                new LambdaQueryWrapper<LearningLesson>()
                        .isNull(LearningLesson::getContentOssKey)
                        .isNotNull(LearningLesson::getContent)
                        .ne(LearningLesson::getContent, ""));
        if (lessons.isEmpty()) {
            log.info("No lessons to migrate.");
            return;
        }
        log.info("Migrating {} lessons to OSS...", lessons.size());
        int migrated = 0, failed = 0;
        for (LearningLesson l : lessons) {
            try {
                String ossName = "tutorials/" + l.getTutorialId() + "/" + l.getId() + ".html";
                byte[] bytes = l.getContent().getBytes(StandardCharsets.UTF_8);
                String key = storageService.upload(
                        new ByteArrayInputStream(bytes),
                        ossName, "text/html; charset=utf-8", bytes.length);
                l.setContentOssKey(key);
                lessonMapper.updateById(l);
                migrated++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to migrate lesson {}: {}", l.getId(), e.getMessage());
            }
        }
        log.info("Lesson migration done: {} migrated, {} failed", migrated, failed);
        TenantContext.clear();
    }

    private void migrateNotes() {
        // 笔记表有租户隔离，必须设置 TenantContext
        TenantContext.setTenantId(1L);
        List<AiNote> notes = noteMapper.selectList(
                new LambdaQueryWrapper<AiNote>()
                        .isNull(AiNote::getContentOssKey)
                        .isNotNull(AiNote::getContent)
                        .ne(AiNote::getContent, ""));
        if (notes.isEmpty()) {
            log.info("No notes to migrate.");
            return;
        }
        log.info("Migrating {} notes to OSS...", notes.size());
        int migrated = 0, failed = 0;
        for (AiNote n : notes) {
            try {
                String ossName = "notes/" + n.getId() + ".md";
                byte[] bytes = n.getContent().getBytes(StandardCharsets.UTF_8);
                String key = storageService.upload(
                        new ByteArrayInputStream(bytes),
                        ossName, "text/markdown; charset=utf-8", bytes.length);
                n.setContentOssKey(key);
                noteMapper.updateById(n);
                migrated++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to migrate note {}: {}", n.getId(), e.getMessage());
            }
        }
        log.info("Note migration done: {} migrated, {} failed", migrated, failed);
        TenantContext.clear();
    }
}
