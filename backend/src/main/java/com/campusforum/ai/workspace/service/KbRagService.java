package com.campusforum.ai.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.ai.workspace.domain.AiKbChunk;
import com.campusforum.ai.workspace.domain.AiKbQaPair;
import com.campusforum.ai.workspace.domain.AiKnowledgeBase;
import com.campusforum.ai.workspace.domain.AiNote;
import com.campusforum.ai.workspace.mapper.AiKbChunkMapper;
import com.campusforum.ai.workspace.mapper.AiKbQaPairMapper;
import com.campusforum.ai.workspace.mapper.AiKnowledgeBaseMapper;
import com.campusforum.ai.workspace.mapper.AiNoteMapper;
import com.campusforum.infra.StorageService;
import com.campusforum.learning.domain.LearningLesson;
import com.campusforum.learning.mapper.LearningLessonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库真实 RAG 服务（2026-07-12 新增，替代"只拼知识库名称"的占位实现）。
 *
 * <p>两条链路：</p>
 * <ol>
 *   <li><b>入库</b> {@link #ingestDocument}：文档上传时抽取正文
 *       （PDF→PDFBox / docx→POI / pptx·xlsx→POI / html→jsoup / 文本直读），
 *       按段落切块（约 {@value #CHUNK_TARGET_CHARS} 字/块）写入 {@code ai_kb_chunks}。
 *       返回切块数即知识库真实 vectorCount。</li>
 *   <li><b>检索</b> {@link #retrieveContext}：对话发消息时按用户 query 的关键词，
 *       对挂载知识库的切块 + 问答对做相关度打分取 top-N，另检索用户本人笔记与
 *       公开教程章节标题命中的正文，拼装为注入 prompt 的上下文（上限
 *       {@value #MAX_CONTEXT_CHARS} 字，防止撑爆模型窗口）。</li>
 * </ol>
 *
 * <p>说明：检索用"分词 + 词频打分"的轻量词法方案而非向量库——校园规模
 * （单库文档数十级）下召回够用，且不引入 embedding 依赖；后续如需语义检索
 * 可在本类内替换打分实现，调用方接口不变。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbRagService {

    /** 单块目标字数（按段落聚合到接近该值）。 */
    private static final int CHUNK_TARGET_CHARS = 800;
    /** 单文档最大切块数，防超大文件刷爆表。 */
    private static final int MAX_CHUNKS_PER_DOC = 500;
    /** 注入 prompt 的上下文总长度上限。 */
    private static final int MAX_CONTEXT_CHARS = 6000;
    /** 检索返回的切块数。 */
    private static final int TOP_CHUNKS = 6;
    /** 抽取文本长度上限（2MB 字符），防恶意超大文档。 */
    private static final int MAX_EXTRACT_CHARS = 2_000_000;

    private final AiKbChunkMapper chunkMapper;
    private final AiKbQaPairMapper qaMapper;
    private final AiKnowledgeBaseMapper kbMapper;
    private final AiNoteMapper noteMapper;
    private final LearningLessonMapper lessonMapper;
    private final StorageService storageService;

    // ======================== 入库链路 ========================

    /**
     * 抽取文档正文并切块入库。抽取失败返回 0（文档仍可下载，只是不参与检索）。
     *
     * @return 切块数（即该文档贡献的 vectorCount）
     */
    @Transactional
    public int ingestDocument(String knowledgeBaseId, String documentId, String fileName, byte[] bytes) {
        String text;
        try {
            text = extractText(fileName, bytes);
        } catch (Exception e) {
            log.warn("Text extraction failed for doc {} ({}): {}", documentId, fileName, e.getMessage());
            return 0;
        }
        if (text == null || text.isBlank()) return 0;
        if (text.length() > MAX_EXTRACT_CHARS) text = text.substring(0, MAX_EXTRACT_CHARS);

        List<String> chunks = chunkText(text);
        int index = 0;
        for (String chunk : chunks) {
            if (index >= MAX_CHUNKS_PER_DOC) break;
            AiKbChunk c = new AiKbChunk();
            c.setKnowledgeBaseId(knowledgeBaseId);
            c.setDocumentId(documentId);
            c.setChunkIndex(index++);
            c.setContent(chunk);
            chunkMapper.insert(c);
        }
        log.info("KB doc ingested: kb={}, doc={}, chunks={}", knowledgeBaseId, documentId, index);
        return index;
    }

    /** 删除文档时级联清理切块；返回被删切块数（用于回补 vectorCount）。 */
    public int removeDocumentChunks(String documentId) {
        return chunkMapper.deleteByDocumentId(documentId);
    }

    /** 删除知识库时级联清理全部切块。 */
    public int removeKnowledgeBaseChunks(String knowledgeBaseId) {
        return chunkMapper.deleteByKnowledgeBaseId(knowledgeBaseId);
    }

    /** 按扩展名分发的正文抽取。 */
    String extractText(String fileName, byte[] bytes) throws Exception {
        String ext = "";
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        switch (ext) {
            case "pdf" -> {
                try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
                    return new PDFTextStripper().getText(doc);
                }
            }
            case "docx" -> {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                    return ex.getText();
                }
            }
            case "pptx" -> {
                try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes));
                     SlideShowExtractor<?, ?> ex = new SlideShowExtractor<>(ppt)) {
                    return ex.getText();
                }
            }
            case "xlsx" -> {
                try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes));
                     XSSFExcelExtractor ex = new XSSFExcelExtractor(wb)) {
                    return ex.getText();
                }
            }
            case "html", "htm" -> {
                return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8)).text();
            }
            // 纯文本族直接按 UTF-8 读（md/txt/csv/json/代码等）
            default -> {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    /** 按空行/换行切段，向 CHUNK_TARGET_CHARS 聚合；超长段落硬切。 */
    List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            // 超长段落先硬切
            while (p.length() > CHUNK_TARGET_CHARS * 2) {
                chunks.add(p.substring(0, CHUNK_TARGET_CHARS * 2));
                p = p.substring(CHUNK_TARGET_CHARS * 2);
            }
            if (current.length() + p.length() > CHUNK_TARGET_CHARS && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(p);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    // ======================== 检索链路 ========================

    /**
     * 检索挂载知识库的切块 + 问答对 + 用户本人笔记 + 公开教程，拼装 prompt 上下文。
     * 无命中返回空串（AI 走通用回答）。
     *
     * @param query  用户当前消息
     * @param kbIds  会话挂载的知识库 ID 列表（用户在前端选择）
     * @param userId 当前用户（校验知识库归属 + 检索本人笔记）
     */
    public String retrieveContext(String query, List<String> kbIds, long userId) {
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder();

        // 1) 知识库文档切块 + 问答对（仅限本人拥有或 shared 的库，防越权检索他人私库）
        if (kbIds != null && !kbIds.isEmpty()) {
            List<String> accessibleKbIds = kbIds.stream()
                    .map(kbMapper::selectById)
                    .filter(Objects::nonNull)
                    .filter(k -> k.getDeleted() == null || k.getDeleted() == 0)
                    .filter(k -> Objects.equals(k.getOwnerId(), userId) || "shared".equals(k.getVisibility()))
                    .map(AiKnowledgeBase::getId)
                    .toList();

            if (!accessibleKbIds.isEmpty()) {
                // 问答对：question 命中即整条注入（人工维护的标准答案优先级最高）
                List<AiKbQaPair> qaPairs = qaMapper.selectList(new LambdaQueryWrapper<AiKbQaPair>()
                        .in(AiKbQaPair::getKnowledgeBaseId, accessibleKbIds).last("LIMIT 500"));
                List<AiKbQaPair> qaHits = qaPairs.stream()
                        .map(qa -> Map.entry(qa, score(qa.getQuestion(), terms) * 2 + score(qa.getAnswer(), terms)))
                        .filter(e -> e.getValue() > 0)
                        .sorted(Comparator.comparingInt(e -> -e.getValue()))
                        .limit(4).map(Map.Entry::getKey).toList();
                if (!qaHits.isEmpty()) {
                    ctx.append("【知识库问答对（人工维护，优先参考）】\n");
                    for (AiKbQaPair qa : qaHits) {
                        ctx.append("Q: ").append(strip(qa.getQuestion(), 200))
                           .append("\nA: ").append(strip(qa.getAnswer(), 800)).append("\n\n");
                    }
                }

                // 文档切块：词频打分取 top-N
                List<AiKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKbChunk>()
                        .in(AiKbChunk::getKnowledgeBaseId, accessibleKbIds).last("LIMIT 3000"));
                List<AiKbChunk> chunkHits = chunks.stream()
                        .map(c -> Map.entry(c, score(c.getContent(), terms)))
                        .filter(e -> e.getValue() > 0)
                        .sorted(Comparator.comparingInt(e -> -e.getValue()))
                        .limit(TOP_CHUNKS).map(Map.Entry::getKey).toList();
                if (!chunkHits.isEmpty()) {
                    ctx.append("【知识库文档片段】\n");
                    for (AiKbChunk c : chunkHits) {
                        if (ctx.length() > MAX_CONTEXT_CHARS) break;
                        ctx.append("- ").append(strip(c.getContent(), 900)).append("\n\n");
                    }
                }
            }
        }

        // 2) 用户本人笔记（标题/标签命中 → 读正文，OSS 兜底）
        if (userId > 0 && ctx.length() < MAX_CONTEXT_CHARS) {
            List<AiNote> notes = noteMapper.selectList(new LambdaQueryWrapper<AiNote>()
                    .eq(AiNote::getOwnerId, userId)
                    .eq(AiNote::getDeleted, 0).last("LIMIT 300"));
            List<AiNote> noteHits = notes.stream()
                    .map(n -> Map.entry(n, score(n.getTitle(), terms) * 3 + score(n.getTags(), terms)))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Comparator.comparingInt(e -> -e.getValue()))
                    .limit(2).map(Map.Entry::getKey).toList();
            if (!noteHits.isEmpty()) {
                ctx.append("【你的相关笔记】\n");
                for (AiNote n : noteHits) {
                    if (ctx.length() > MAX_CONTEXT_CHARS) break;
                    ctx.append("《").append(n.getTitle()).append("》\n")
                       .append(strip(resolveNoteContent(n), 1200)).append("\n\n");
                }
            }
        }

        // 3) 公开教程章节（标题命中 → 读正文，OSS 兜底）
        if (ctx.length() < MAX_CONTEXT_CHARS) {
            List<LearningLesson> lessons = lessonMapper.selectList(
                    new LambdaQueryWrapper<LearningLesson>().last("LIMIT 800"));
            List<LearningLesson> lessonHits = lessons.stream()
                    .map(l -> Map.entry(l, score(l.getTitle(), terms)))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Comparator.comparingInt(e -> -e.getValue()))
                    .limit(2).map(Map.Entry::getKey).toList();
            if (!lessonHits.isEmpty()) {
                ctx.append("【站内教程相关章节】\n");
                for (LearningLesson l : lessonHits) {
                    if (ctx.length() > MAX_CONTEXT_CHARS) break;
                    ctx.append("《").append(l.getTitle()).append("》\n")
                       .append(strip(resolveLessonContent(l), 1000)).append("\n\n");
                }
            }
        }

        if (ctx.length() == 0) return "";
        String result = ctx.length() > MAX_CONTEXT_CHARS ? ctx.substring(0, MAX_CONTEXT_CHARS) : ctx.toString();
        return "以下是与用户问题相关的参考资料，回答时请优先依据这些内容：\n\n" + result;
    }

    // ======================== 打分与工具 ========================

    /**
     * 轻量分词：ASCII 单词整词 + 中文按 2-gram。
     * 例："Spring多租户隔离" → [spring, 多租, 租户, 户隔, 隔离]
     */
    Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null) return terms;
        String q = query.toLowerCase(Locale.ROOT);
        // ASCII 单词（≥2 字符）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9_]{2,}").matcher(q);
        while (m.find()) terms.add(m.group());
        // 中文 2-gram
        StringBuilder cjk = new StringBuilder();
        for (char ch : q.toCharArray()) {
            if (ch >= 0x4E00 && ch <= 0x9FFF) cjk.append(ch);
            else if (cjk.length() > 0) { addGrams(terms, cjk); cjk.setLength(0); }
        }
        if (cjk.length() > 0) addGrams(terms, cjk);
        return terms;
    }

    private void addGrams(Set<String> terms, StringBuilder cjk) {
        if (cjk.length() == 1) { terms.add(cjk.toString()); return; }
        for (int i = 0; i + 2 <= cjk.length(); i++) terms.add(cjk.substring(i, i + 2));
    }

    /** 词频打分：文本中每命中一个 term 计 1 分（每 term 上限 3 次防刷分）。 */
    int score(String text, Set<String> terms) {
        if (text == null || text.isEmpty()) return 0;
        String t = text.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String term : terms) {
            int count = 0;
            int idx = 0;
            while ((idx = t.indexOf(term, idx)) >= 0 && count < 3) { count++; idx += term.length(); }
            total += count;
        }
        return total;
    }

    private String strip(String text, int max) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private String resolveNoteContent(AiNote note) {
        if (note.getContentOssKey() != null && !note.getContentOssKey().isBlank()) {
            try (InputStream is = storageService.download(note.getContentOssKey())) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("OSS read failed for note {}: {}", note.getId(), e.getMessage());
            }
        }
        return note.getContent() == null ? "" : note.getContent();
    }

    private String resolveLessonContent(LearningLesson lesson) {
        if (lesson.getContentOssKey() != null && !lesson.getContentOssKey().isBlank()) {
            try (InputStream is = storageService.download(lesson.getContentOssKey())) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("OSS read failed for lesson {}: {}", lesson.getId(), e.getMessage());
            }
        }
        return lesson.getContent() == null ? "" : lesson.getContent();
    }
}
