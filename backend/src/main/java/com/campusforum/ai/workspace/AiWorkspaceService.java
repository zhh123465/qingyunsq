package com.campusforum.ai.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.ai.service.AiService;
import com.campusforum.ai.workspace.domain.*;
import com.campusforum.infra.StorageService;
import com.campusforum.ai.workspace.mapper.*;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 工作台服务——2026-06-24 从 JSON 内存存储迁移到 MySQL。
 * 对外接口（Controller 调用的方法签名与返回值）保持不变，前端零改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWorkspaceService {

    private final AiService aiService;
    private final AiKnowledgeBaseMapper kbMapper;
    private final AiKbDocumentMapper docMapper;
    private final AiKbQaPairMapper qaMapper;
    private final AiIngestTaskMapper taskMapper;
    private final AiConversationMapper convMapper;
    private final AiMessageMapper msgMapper;
    private final AiUserFavoriteMapper favMapper;
    private final AiNoteMapper noteMapper;
    private final UserMapper userMapper;
    private final StorageService storageService;
    private final com.campusforum.ai.workspace.service.KbRagService kbRagService;
    private final com.campusforum.ai.workspace.service.NoteReviewNotifier noteReviewNotifier;

    /** Self reference for AOP self-invocation (per-item transaction in batch purge). */
    @Resource
    @Lazy
    private AiWorkspaceService selfProxy;

    /** 预定义学习笔记标签，与前端 note-tags.ts 保持一致。 */
    public static final List<String> NOTE_TAGS = List.of(
        "前端学习", "后端开发", "数据库", "运维DevOps", "开发工具",
        "计算机基础", "复习资料", "期末复习", "考研经验", "学习方法",
        "实习求职", "校园生活", "其他"
    );

    // 智能体（Agents）与插件市场（Plugins）已于 2026-07-12 整体删除（前端从未接入）。

    // ======================== Knowledge Bases ========================

    public Map<String, Object> listKnowledgeBases(String keyword, String category, String tab,
                                                  String type, String sort, int page, int pageSize) {
        long userId = userIdOrGuest();
        // 默认只返回当前用户自己的知识库，防止漏传/兜底 tab 参数导致他人 private KB 被列出。
        // 显式指定 shared/favorite 时才走公开/收藏语义；null/空/"all" 一律收敛为 mine。
        String effectiveTab = (blank(tab) || "all".equals(tab)) ? "mine" : tab;
        LambdaQueryWrapper<AiKnowledgeBase> q = new LambdaQueryWrapper<AiKnowledgeBase>()
                .eq(AiKnowledgeBase::getDeleted, 0);
        if (!blank(category)) q.eq(AiKnowledgeBase::getCategory, category);
        if (!blank(type)) q.eq(AiKnowledgeBase::getType, type);
        if ("mine".equals(effectiveTab)) q.eq(AiKnowledgeBase::getOwnerId, userId);
        List<AiKnowledgeBase> all = kbMapper.selectList(q);
        List<Map<String, Object>> list = all.stream()
                .filter(k -> matchesStr(k.getName(), k.getDescription(), k.getCategory(), keyword))
                .filter(k -> kbTab(k, effectiveTab, userId))
                .map(k -> kbView(k, userId))
                .collect(Collectors.toCollection(ArrayList::new));
        sortKnowledgeBases(list, sort);
        return page(list, page, pageSize);
    }

    public Map<String, Object> knowledgeStats() {
        // 页面顶部"X 个知识库·Y 份文档·Z 已用"统计当前用户自己的 KB，而非全租户全量。
        long userId = userIdOrGuest();
        List<AiKnowledgeBase> all = kbMapper.selectList(
                new LambdaQueryWrapper<AiKnowledgeBase>()
                        .eq(AiKnowledgeBase::getDeleted, 0)
                        .eq(AiKnowledgeBase::getOwnerId, userId));
        long docs = all.stream().mapToLong(k -> k.getDocumentCount() == null ? 0 : k.getDocumentCount()).sum();
        long vectors = all.stream().mapToLong(k -> k.getVectorCount() == null ? 0 : k.getVectorCount()).sum();
        long storage = all.stream().mapToLong(k -> k.getStorageBytes() == null ? 0 : k.getStorageBytes()).sum();
        return Map.of("knowledgeBaseCount", all.size(), "documentCount", docs,
                "vectorCount", vectors, "storageUsedBytes", storage, "storageLimitBytes", 53687091200L);
    }

    @Transactional
    public Map<String, Object> createKnowledgeBase(Map<String, Object> body) {
        long userId = requireUser();
        AiKnowledgeBase k = new AiKnowledgeBase();
        k.setId("kb_" + shortId());
        k.setName(str(body.get("name"), "Untitled Knowledge Base"));
        k.setDescription(str(body.get("description"), ""));
        k.setCategory(str(body.get("category"), "General"));
        k.setType(str(body.get("type"), str(body.get("category"), "General")));
        k.setVisibility(str(body.get("visibility"), "private"));
        k.setDocumentCount(0L); k.setVectorCount(0L); k.setStorageBytes(0L); k.setQaPairCount(0L);
        k.setOwnerId(userId);
        k.setDeleted(0);
        kbMapper.insert(k);
        return kbView(k, userId);
    }

    @Transactional
    public Map<String, Object> updateKnowledgeBase(String knowledgeBaseId, Map<String, Object> body) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        patchEntity(k, body, "name", "description", "category", "type", "visibility");
        kbMapper.updateById(k);
        return kbView(k, userId);
    }

    @Transactional
    public Map<String, Object> deleteKnowledgeBase(String knowledgeBaseId) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        k.setDeleted(1);
        kbMapper.updateById(k);
        kbRagService.removeKnowledgeBaseChunks(knowledgeBaseId);
        return Map.of("id", knowledgeBaseId, "deleted", true);
    }

    @Transactional
    public Map<String, Object> favoriteKnowledgeBase(String knowledgeBaseId, boolean favorite) {
        long userId = requireUser();
        requireKb(knowledgeBaseId);
        if (favorite) {
            AiUserFavorite f = new AiUserFavorite();
            f.setUserId(userId); f.setFavoriteType("knowledge_base"); f.setTargetId(knowledgeBaseId);
            favMapper.insert(f);
        } else {
            favMapper.removeFavorite(userId, "knowledge_base", knowledgeBaseId);
        }
        return Map.of("id", knowledgeBaseId, "isFavorite", favorite);
    }

    public Map<String, Object> shareKnowledgeBase(String knowledgeBaseId, Map<String, Object> body) {
        requireUser();
        requireKb(knowledgeBaseId);
        return Map.of("shareId", "share_" + shortId(), "knowledgeBaseId", knowledgeBaseId,
                "targetUserIds", list(body.get("targetUserIds")),
                "permission", str(body.get("permission"), "read"),
                "url", "/ai/knowledge-bases/" + knowledgeBaseId,
                "expiresAt", OffsetDateTime.now().plusDays(7).toString());
    }

    @Transactional
    public Map<String, Object> uploadDocuments(String knowledgeBaseId, MultipartFile[] files,
                                               String tags, String parseMode) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        int uploaded = 0;
        long bytes = 0;
        long totalChunks = 0;
        int parseFailed = 0;
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                // 读一次字节流：既上传 OSS 又做文本抽取/切块（真实 RAG，2026-07-12）
                byte[] fileBytes;
                try {
                    fileBytes = file.getBytes();
                } catch (Exception e) {
                    log.warn("Read upload failed for KB document {}: {}", file.getOriginalFilename(), e.getMessage());
                    continue;
                }
                String storageKey = null;
                try {
                    storageKey = storageService.upload(
                            new java.io.ByteArrayInputStream(fileBytes),
                            file.getOriginalFilename(),
                            file.getContentType(),
                            fileBytes.length);
                } catch (Exception e) {
                    log.warn("OSS upload failed for KB document {}: {}", file.getOriginalFilename(), e.getMessage());
                }
                AiKbDocument d = new AiKbDocument();
                d.setId("doc_" + shortId());
                d.setKnowledgeBaseId(knowledgeBaseId);
                d.setFileName(file.getOriginalFilename());
                d.setFileSize(file.getSize());
                d.setStorageKey(storageKey);
                d.setTags(toJson(tags == null ? List.of() : List.of(tags.split(","))));
                d.setParseMode(blank(parseMode) ? "auto" : parseMode);
                int chunks = 0;
                if (storageKey != null) {
                    chunks = kbRagService.ingestDocument(knowledgeBaseId, d.getId(),
                            file.getOriginalFilename(), fileBytes);
                    if (chunks == 0) parseFailed++;
                }
                d.setStatus(storageKey == null ? "upload_failed" : (chunks > 0 ? "ready" : "parse_failed"));
                docMapper.insert(d);
                if (storageKey != null) {
                    uploaded++;
                    bytes += file.getSize();
                    totalChunks += chunks;
                }
            }
        }
        k.setDocumentCount((k.getDocumentCount() == null ? 0 : k.getDocumentCount()) + uploaded);
        // vectorCount 现在是真实切块数（旧版是 uploaded*512 的假数字）
        k.setVectorCount((k.getVectorCount() == null ? 0 : k.getVectorCount()) + totalChunks);
        k.setStorageBytes((k.getStorageBytes() == null ? 0 : k.getStorageBytes()) + bytes);
        kbMapper.updateById(k);
        AiIngestTask t = new AiIngestTask();
        t.setTaskId("task_" + shortId());
        t.setKnowledgeBaseId(knowledgeBaseId);
        t.setStatus("completed");
        t.setProgress(100);
        t.setMessage(parseFailed == 0
                ? "已解析并切块 " + totalChunks + " 个片段"
                : "已入库，其中 " + parseFailed + " 个文档正文抽取失败（仍可下载，不参与检索）");
        taskMapper.insert(t);
        return Map.of("taskId", t.getTaskId(), "uploaded", uploaded, "chunks", totalChunks);
    }

    public List<Map<String, Object>> listDocuments(String knowledgeBaseId) {
        requireKb(knowledgeBaseId);
        return docMapper.selectList(new LambdaQueryWrapper<AiKbDocument>()
                .eq(AiKbDocument::getKnowledgeBaseId, knowledgeBaseId)).stream()
                .map(this::docMap).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> deleteDocument(String knowledgeBaseId, String documentId) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        AiKbDocument d = docMapper.selectById(documentId);
        if (d == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        docMapper.deleteById(documentId);
        int removedChunks = kbRagService.removeDocumentChunks(documentId);
        k.setDocumentCount(Math.max(0, (k.getDocumentCount() == null ? 0 : k.getDocumentCount()) - 1));
        k.setVectorCount(Math.max(0, (k.getVectorCount() == null ? 0 : k.getVectorCount()) - removedChunks));
        kbMapper.updateById(k);
        return Map.of("id", documentId, "deleted", true);
    }

    public Map<String, Object> ingestTask(String taskId) {
        AiIngestTask t = taskMapper.selectById(taskId);
        if (t == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return Map.of("taskId", t.getTaskId(), "knowledgeBaseId", t.getKnowledgeBaseId(),
                "status", t.getStatus(), "progress", t.getProgress(), "message", str(t.getMessage(), ""));
    }

    @Transactional
    public Map<String, Object> createQaPair(String knowledgeBaseId, Map<String, Object> body) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        AiKbQaPair qa = new AiKbQaPair();
        qa.setId("qa_" + shortId());
        qa.setKnowledgeBaseId(knowledgeBaseId);
        qa.setQuestion(str(body.get("question"), ""));
        qa.setAnswer(str(body.get("answer"), ""));
        qa.setTags(toJson(list(body.get("tags"))));
        qaMapper.insert(qa);
        k.setQaPairCount((k.getQaPairCount() == null ? 0 : k.getQaPairCount()) + 1);
        kbMapper.updateById(k);
        return Map.of("id", qa.getId(), "question", qa.getQuestion(),
                "answer", qa.getAnswer(), "tags", fromJsonList(qa.getTags()), "createdAt", now());
    }

    public Map<String, Object> knowledgeUsage(String knowledgeBaseId) {
        requireKb(knowledgeBaseId);
        List<String> hotQuestions = qaMapper.selectList(
                new LambdaQueryWrapper<AiKbQaPair>().eq(AiKbQaPair::getKnowledgeBaseId, knowledgeBaseId))
                .stream().map(AiKbQaPair::getQuestion).limit(10).toList();
        return Map.of("knowledgeBaseId", knowledgeBaseId, "callCount", 0,
                "hitRate", 0.0, "noAnswerRate", 0.0, "hotQuestions", hotQuestions);
    }

    public Map<String, Object> getKnowledgeBase(String knowledgeBaseId) {
        long userId = userIdOrGuest();
        return kbView(requireKb(knowledgeBaseId), userId);
    }

    public List<Map<String, Object>> listQaPairs(String knowledgeBaseId) {
        requireKb(knowledgeBaseId);
        return qaMapper.selectList(
                new LambdaQueryWrapper<AiKbQaPair>().eq(AiKbQaPair::getKnowledgeBaseId, knowledgeBaseId))
                .stream().map(this::qaMap).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> updateQaPair(String knowledgeBaseId, String qaPairId, Map<String, Object> body) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        AiKbQaPair qa = qaMapper.selectById(qaPairId);
        if (qa == null || !qa.getKnowledgeBaseId().equals(knowledgeBaseId))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (body.containsKey("question")) qa.setQuestion(str(body.get("question"), ""));
        if (body.containsKey("answer")) qa.setAnswer(str(body.get("answer"), ""));
        if (body.containsKey("tags")) qa.setTags(toJson(list(body.get("tags"))));
        qaMapper.updateById(qa);
        return qaMap(qa);
    }

    @Transactional
    public Map<String, Object> deleteQaPair(String knowledgeBaseId, String qaPairId) {
        long userId = requireUser();
        AiKnowledgeBase k = requireKb(knowledgeBaseId);
        requireOwner(k.getOwnerId(), userId);
        AiKbQaPair qa = qaMapper.selectById(qaPairId);
        if (qa == null || !qa.getKnowledgeBaseId().equals(knowledgeBaseId))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        qaMapper.deleteById(qaPairId);
        k.setQaPairCount(Math.max(0, (k.getQaPairCount() == null ? 0 : k.getQaPairCount()) - 1));
        kbMapper.updateById(k);
        return Map.of("id", qaPairId, "deleted", true);
    }

    public Map<String, Object> getDocumentDetail(String knowledgeBaseId, String documentId) {
        requireKb(knowledgeBaseId);
        AiKbDocument doc = docMapper.selectById(documentId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(knowledgeBaseId))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return docMap(doc);
    }

    // ======================== Notes ========================

    public Map<String, Object> getNote(String noteId) {
        AiNote note = requireNote(noteId);
        Map<String, Object> view = noteView(note, userIdOrGuest());
        view.put("content", resolveNoteContent(note)); // 从 OSS 读取完整内容
        return view;
    }

    @Transactional
    public Map<String, Object> createNote(Map<String, Object> body) {
        long userId = requireUser();
        String rawContent = str(body.get("content"), "");
        // 审核流（2026-07-16）：用户可提交的状态只有 draft/published；
        // 普通用户请求 published 强制落 pending 待审，管理员免审（与资源上传一致）
        String requestedStatus = str(body.get("status"), "draft");
        assertUserSettableStatus(requestedStatus);
        String status = "published".equals(requestedStatus) && !isAdminRole()
                ? "pending" : requestedStatus;
        AiNote note = new AiNote();
        note.setId("note_" + shortId());
        note.setTitle(str(body.get("title"), "未命名笔记"));
        note.setContentType(str(body.get("contentType"), "markdown"));
        note.setTags(toJson(list(body.get("tags"))));
        note.setStatus(status);
        note.setViewCount(0L);
        note.setOwnerId(userId);
        note.setKnowledgeBaseId(str(body.get("knowledgeBaseId"), null));
        note.setSourceUrl(str(body.get("sourceUrl"), ""));
        note.setSourceName(str(body.get("sourceName"), ""));
        note.setSourceAuthor(str(body.get("sourceAuthor"), ""));
        note.setDeleted(0);
        if (!rawContent.isBlank()) {
            note.setContentOssKey(uploadNoteContent(note.getId(), rawContent));
            note.setContent(null); // 内容已迁移到 OSS
        } else {
            note.setContent("");
        }
        noteMapper.insert(note);
        if ("pending".equals(status)) {
            notifyNotePendingQuietly(note);
        }
        Map<String, Object> view = noteView(note, userId);
        view.put("content", rawContent); // API 兼容：返回完整内容
        return view;
    }

    @Transactional
    public Map<String, Object> updateNote(String noteId, Map<String, Object> body) {
        long userId = requireUser();
        AiNote note = requireNote(noteId);
        if (!Objects.equals(note.getOwnerId(), userId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        String statusBefore = str(note.getStatus(), "draft");
        String requestedStatus = body.containsKey("status") ? str(body.get("status"), "draft") : null;
        if (requestedStatus != null) assertUserSettableStatus(requestedStatus);
        boolean substanceChanged = false; // 标题或正文实质变化 → 已发布笔记打回重审
        if (body.containsKey("title")) {
            String newTitle = str(body.get("title"), "");
            if (!newTitle.equals(str(note.getTitle(), ""))) substanceChanged = true;
            note.setTitle(newTitle);
        }
        if (body.containsKey("content")) {
            String newContent = str(body.get("content"), null);
            // 与旧正文对比（须在删除旧 OSS 对象前读取；OSS 读失败按"已变化"处理，宁可多审不漏审）
            if (!str(newContent, "").equals(resolveNoteContent(note))) substanceChanged = true;
            if (newContent != null && !newContent.isBlank()) {
                // 删除旧 OSS 文件
                deleteNoteOssContent(note);
                // 上传新内容到 OSS
                note.setContentOssKey(uploadNoteContent(noteId, newContent));
                note.setContent(null);
            } else {
                deleteNoteOssContent(note);
                note.setContentOssKey(null);
                note.setContent("");
            }
        }
        if (body.containsKey("contentType")) note.setContentType(str(body.get("contentType"), "markdown"));
        if (body.containsKey("tags")) note.setTags(toJson(list(body.get("tags"))));
        if (body.containsKey("knowledgeBaseId")) note.setKnowledgeBaseId(str(body.get("knowledgeBaseId"), null));
        if (body.containsKey("sourceUrl"))    note.setSourceUrl(str(body.get("sourceUrl"), ""));
        if (body.containsKey("sourceName"))   note.setSourceName(str(body.get("sourceName"), ""));
        if (body.containsKey("sourceAuthor")) note.setSourceAuthor(str(body.get("sourceAuthor"), ""));
        note.setStatus(resolveUpdatedStatus(statusBefore, requestedStatus, substanceChanged));
        if ("pending".equals(note.getStatus())) note.setReviewReason(null); // 进入新一轮审核，清旧驳回原因
        noteMapper.updateById(note);
        // 仅在状态由非 pending 变为 pending 时通知，pending 中反复保存不重复轰炸管理员
        if ("pending".equals(note.getStatus()) && !"pending".equals(statusBefore)) {
            notifyNotePendingQuietly(note);
        }
        Map<String, Object> view = noteView(note, userId);
        // API 兼容：返回完整内容
        view.put("content", resolveNoteContent(note));
        return view;
    }

    /**
     * 审核流（2026-07-16）：计算 updateNote 后的笔记状态。
     * <ul>
     *   <li>未显式传 status：保持原状态；但已发布笔记的标题/正文被普通用户改动 → 打回 pending 重审
     *       （堵住"先过审再改内容"绕过审核的口子）</li>
     *   <li>请求 draft：直接存草稿</li>
     *   <li>请求 published：管理员免审直接发布；普通用户已发布且无实质改动保持 published
     *       （避免原样保存被无谓打回），其余情况（首次发布 / 驳回后重投 / 已发布但改了内容）
     *       一律落 pending 待审</li>
     * </ul>
     */
    private String resolveUpdatedStatus(String statusBefore, String requestedStatus, boolean substanceChanged) {
        boolean admin = isAdminRole();
        if (requestedStatus == null) {
            if (!admin && substanceChanged && "published".equals(statusBefore)) return "pending";
            return statusBefore;
        }
        if ("draft".equals(requestedStatus)) return "draft";
        // requestedStatus == published
        if (admin) return "published";
        if ("published".equals(statusBefore) && !substanceChanged) return "published";
        return "pending";
    }

    /** 用户（作者）可提交的状态白名单：draft / published。pending/rejected/hidden 由系统与管理员掌控。 */
    private static void assertUserSettableStatus(String status) {
        if (!"draft".equals(status) && !"published".equals(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "无效的笔记状态，仅允许 draft/published");
        }
    }

    /** 通知失败不影响保存主流程（与资源审核流同款约定）。 */
    private void notifyNotePendingQuietly(AiNote note) {
        try {
            noteReviewNotifier.onNotePending(note);
        } catch (Exception e) {
            log.warn("Note review notify failed for id={}: {}", note.getId(), e.getMessage());
        }
    }

    private static boolean isAdminRole() {
        String role = currentRoleOrNull();
        return "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    /** 与 ResourceService 同款：登录用户的角色从 Sa-Token session 读，游客/异常返回 null。 */
    private static String currentRoleOrNull() {
        try {
            if (!StpUtil.isLogin()) return null;
            return (String) StpUtil.getSession().get("role");
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public Map<String, Object> deleteNote(String noteId) {
        long userId = requireUser();
        AiNote note = requireNote(noteId);
        if (!Objects.equals(note.getOwnerId(), userId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        deleteNoteOssContent(note);
        // 使用 raw SQL 绕过 MyBatis-Plus 全局逻辑删除插件，
        // 直接执行 UPDATE ai_notes SET deleted=1 WHERE id=? AND deleted=0
        noteMapper.deleteNoteDirect(noteId);
        return Map.of("id", noteId, "deleted", true);
    }

    public Map<String, Object> listNotes(String keyword, String status, int page, int pageSize) {
        long userId = userIdOrGuest();
        LambdaQueryWrapper<AiNote> q = new LambdaQueryWrapper<AiNote>()
                .eq(AiNote::getDeleted, 0)
                .eq(AiNote::getOwnerId, userId);
        if (!blank(status)) q.eq(AiNote::getStatus, status);
        List<AiNote> all = noteMapper.selectList(q);
        List<Map<String, Object>> list = all.stream()
                .filter(n -> matchesStr(n.getTitle(), "", "", keyword))
                .map(n -> noteView(n, userId))
                .sorted(Comparator.comparing(v -> str(v.get("updatedAt"), ""), Comparator.reverseOrder()))
                .collect(Collectors.toCollection(ArrayList::new));
        return page(list, page, pageSize);
    }

    // ---- Public notes (for learning page) ----

    public Map<String, Object> listPublicNotes(String keyword, String tag, boolean mine, int page, int pageSize) {
        long userId = userIdOrGuest();
        LambdaQueryWrapper<AiNote> q = new LambdaQueryWrapper<AiNote>()
                .eq(AiNote::getDeleted, 0)
                .eq(AiNote::getStatus, "published");
        if (mine && userId != 0L) {
            q.eq(AiNote::getOwnerId, userId);
        }
        List<AiNote> all = noteMapper.selectList(q);
        // 排序：先按 sortOrder 倒序（数值大=管理员置顶靠前，默认 0），再按 updatedAt 倒序
        List<Map<String, Object>> list = all.stream()
                .filter(n -> matchesStr(n.getTitle(), "", "", keyword))
                .filter(n -> blank(tag) || fromJsonList(n.getTags()).contains(tag))
                .sorted(Comparator
                        .comparingInt((AiNote n) -> n.getSortOrder() == null ? 0 : n.getSortOrder()).reversed()
                        .thenComparing(n -> n.getUpdatedAt() == null ? "" : n.getUpdatedAt().toString(),
                                Comparator.reverseOrder()))
                .map(n -> noteView(n, userId))
                .collect(Collectors.toCollection(ArrayList::new));
        return page(list, page, pageSize);
    }

    /**
     * 管理员在学习页"编辑排序"提交后调用：按传入 ids 顺序，把每条笔记的 sort_order 更新为
     * {@code (ids.size() - index)}，即数组第 0 位 → 最大权重，最后一位 → 权重 1。
     * 未在 ids 中的笔记保持原 sort_order（默认 0）。
     * 用于 {@link com.campusforum.learning.controller.LearningController} 的 reorder 端点。
     */
    @Transactional
    public int adminReorderNotes(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int total = ids.size();
        int updated = 0;
        for (int i = 0; i < total; i++) {
            AiNote n = noteMapper.selectById(ids.get(i));
            if (n == null) continue;
            n.setSortOrder(total - i);
            noteMapper.updateById(n);
            updated++;
        }
        return updated;
    }

    /** 返回预定义学习笔记标签列表。 */
    public List<String> getNoteTags() {
        return NOTE_TAGS;
    }

    public Map<String, Object> getPublicNote(String noteId) {
        AiNote note = noteMapper.selectById(noteId);
        if (note == null || note.getDeleted() != null && note.getDeleted() == 1)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        long viewerId = userIdOrGuest();
        boolean published = "published".equals(note.getStatus());
        // 审核流（2026-07-16）：非 published 仅作者本人（预览自己的待审/驳回稿）
        // 和管理员（审核时看内容）可见，匿名与其他用户一律 404
        if (!published && !Objects.equals(note.getOwnerId(), viewerId) && !isAdminRole())
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (published) {
            // Increment view count（仅公开状态计数，审核预览不掺水）
            note.setViewCount((note.getViewCount() == null ? 0 : note.getViewCount()) + 1);
            noteMapper.updateById(note);
        }
        Map<String, Object> view = noteView(note, viewerId);
        view.put("content", resolveNoteContent(note)); // OSS 完整内容
        return view;
    }

    // ======================== Notes: Admin ========================

    /** 合法的 ai_notes.status 值。'hidden' 是管理端隐藏语义；'pending'/'rejected' 是审核流（2026-07-16）。 */
    private static final Set<String> ADMIN_NOTE_STATUSES = Set.of("draft", "pending", "published", "rejected", "hidden");

    public PageResult<Map<String, Object>> adminListNotes(String keyword, String status, Long ownerId,
                                                          int page, int pageSize) {
        LambdaQueryWrapper<AiNote> q = new LambdaQueryWrapper<AiNote>()
                .eq(AiNote::getDeleted, 0);
        if (!blank(status)) q.eq(AiNote::getStatus, status);
        if (ownerId != null) q.eq(AiNote::getOwnerId, ownerId);
        long viewerId = userIdOrGuest();
        List<Map<String, Object>> list = noteMapper.selectList(q).stream()
                .filter(n -> matchesStr(n.getTitle(), "", "", keyword))
                .map(n -> noteView(n, viewerId))
                .sorted(Comparator.comparing(v -> str(v.get("updatedAt"), ""), Comparator.reverseOrder()))
                .collect(Collectors.toCollection(ArrayList::new));
        return pageResult(list, page, pageSize);
    }

    public PageResult<Map<String, Object>> adminListNotesTrash(String keyword, Long ownerId,
                                                               int page, int pageSize) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiNote> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.lambda().eq(ownerId != null, AiNote::getOwnerId, ownerId)
                .orderByDesc(AiNote::getUpdatedAt);
        long viewerId = userIdOrGuest();
        List<Map<String, Object>> list = noteMapper.selectTrash(qw).stream()
                .filter(n -> blank(keyword) || matchesStr(n.getTitle(), "", "", keyword))
                .map(n -> noteView(n, viewerId))
                .collect(Collectors.toCollection(ArrayList::new));
        return pageResult(list, page, pageSize);
    }

    @Transactional
    public void adminSetNoteStatus(String noteId, String status) {
        if (status == null || !ADMIN_NOTE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "无效的笔记状态，仅允许 draft/pending/published/rejected/hidden");
        }
        AiNote note = noteMapper.selectById(noteId);
        if (note == null || (note.getDeleted() != null && note.getDeleted() == 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        note.setStatus(status);
        noteMapper.updateById(note);
    }

    // === 审核流（2026-07-16）：通过 / 驳回 / 批量通过，照 ResourceService 同款模式 ===

    /** 审核通过：→ published，清驳回原因、记审核人/时间，站内通知作者。幂等：已发布直接返回。 */
    @Transactional
    public void adminApproveNote(String noteId, Long reviewerId) {
        AiNote note = noteMapper.selectById(noteId);
        if (note == null || (note.getDeleted() != null && note.getDeleted() == 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if ("published".equals(note.getStatus())) {
            return; // 幂等：已发布不重复处理
        }
        note.setStatus("published");
        note.setReviewReason(null);
        note.setReviewedBy(reviewerId);
        note.setReviewedAt(LocalDateTime.now());
        noteMapper.updateById(note);
        try {
            noteReviewNotifier.onReviewResult(note, true, null, reviewerId);
        } catch (Exception e) {
            log.warn("Note approve notify failed for id={}: {}", noteId, e.getMessage());
        }
        log.info("Note approved: id={}, reviewer={}", noteId, reviewerId);
    }

    /** 审核驳回：→ rejected，记录原因并站内通知作者（含原因）。 */
    @Transactional
    public void adminRejectNote(String noteId, Long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "驳回原因不能为空");
        }
        AiNote note = noteMapper.selectById(noteId);
        if (note == null || (note.getDeleted() != null && note.getDeleted() == 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        note.setStatus("rejected");
        note.setReviewReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        note.setReviewedBy(reviewerId);
        note.setReviewedAt(LocalDateTime.now());
        noteMapper.updateById(note);
        try {
            noteReviewNotifier.onReviewResult(note, false, note.getReviewReason(), reviewerId);
        } catch (Exception e) {
            log.warn("Note reject notify failed for id={}: {}", noteId, e.getMessage());
        }
        log.info("Note rejected: id={}, reviewer={}", noteId, reviewerId);
    }

    /** 批量审核通过：逐条走 approve 保证通知同步，失败的记录 id 返回。 */
    public Map<String, Object> adminApproveNoteBatch(List<String> ids, Long reviewerId) {
        int success = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                selfProxy.adminApproveNote(id, reviewerId);
                success++;
            } catch (Exception e) {
                log.warn("batch approve note failed: id={}, msg={}", id, e.getMessage());
                failed.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return result;
    }

    @Transactional
    public void adminDeleteNote(String noteId) {
        AiNote note = noteMapper.selectById(noteId);
        if (note == null || (note.getDeleted() != null && note.getDeleted() == 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // 走原有 raw SQL 软删（deleted=0 → 1），不删 OSS 文件（留给 purge）
        noteMapper.deleteNoteDirect(noteId);
    }

    @Transactional
    public void adminRestoreNote(String noteId) {
        int rows = noteMapper.restoreById(noteId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    /** 彻底删除：先清 OSS 内容，再物理删记录。返回一个占位 counts，方便审计。 */
    @Transactional
    public Map<String, Integer> adminPurgeNote(String noteId) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiNote> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.lambda().eq(AiNote::getId, noteId);
        List<AiNote> found = noteMapper.selectTrash(qw);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(),
                    "笔记不在回收站或已被清理");
        }
        try {
            deleteNoteOssContent(found.get(0));
        } catch (Exception e) {
            log.warn("Purge note {} but OSS content delete failed: {}", noteId, e.getMessage());
        }
        int self = noteMapper.physicalDeleteById(noteId);
        if (self == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "笔记已被清理");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ossContent", 1);
        return counts;
    }

    // === 批量管理端笔记操作 ===
    public int adminSetNoteStatusBatch(List<String> ids, String status) {
        if (status == null || !ADMIN_NOTE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "无效的笔记状态，仅允许 draft/pending/published/rejected/hidden");
        }
        return noteMapper.batchSetStatus(ids, status);
    }

    public int adminDeleteNoteBatch(List<String> ids) {
        return noteMapper.batchLogicalDelete(ids);
    }

    public int adminRestoreNoteBatch(List<String> ids) {
        return noteMapper.batchRestore(ids);
    }

    public Map<String, Object> adminPurgeNoteBatch(List<String> ids) {
        int success = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Integer> totalCounts = new LinkedHashMap<>();
        for (String id : ids) {
            try {
                Map<String, Integer> c = selfProxy.adminPurgeNote(id);
                success++;
                c.forEach((k, v) -> totalCounts.merge(k, v, Integer::sum));
            } catch (BusinessException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", e.getMessage() != null ? e.getMessage() : "PURGE_FAILED");
                failed.add(f);
            } catch (Exception e) {
                log.warn("purge note failed: id={}, msg={}", id, e.getMessage());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", "PURGE_FAILED");
                failed.add(f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("counts", totalCounts);
        return result;
    }

    // ======================== Conversations ========================

    @Transactional
    public Map<String, Object> createConversation(Map<String, Object> body) {
        long userId = requireUser();
        AiConversation c = new AiConversation();
        c.setId("chat_" + shortId());
        c.setTitle(str(body.get("title"), "New conversation"));
        c.setModel(str(body.get("model"), "deepseek-v4-flash"));
        c.setKnowledgeBaseIds(toJson(list(body.get("knowledgeBaseIds"))));
        c.setOwnerId(userId);
        convMapper.insert(c);
        return conversationView(c);
    }

    public Map<String, Object> listConversations(int page, int pageSize) {
        long userId = requireUser();
        List<Map<String, Object>> list = convMapper.selectList(
                new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getOwnerId, userId)
                        .orderByDesc(AiConversation::getUpdatedAt)).stream()
                .map(this::conversationView)
                .collect(Collectors.toList());
        return page(list, page, pageSize);
    }

    public List<Map<String, Object>> conversationMessages(String conversationId) {
        long userId = requireUser();
        requireConvOwner(conversationId, userId);
        return msgMapper.findByConversationId(conversationId).stream()
                .map(this::messageMap).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> sendMessage(String conversationId, Map<String, Object> body) {
        long userId = requireUser();
        AiConversation c = requireConvOwner(conversationId, userId);
        String content = str(body.get("content"), "");
        String model = str(body.get("model"), str(c.getModel(), "deepseek-v4-flash"));

        AiMessage userMsg = new AiMessage();
        userMsg.setId("msg_" + shortId());
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(content);
        userMsg.setModel(model);
        userMsg.setAttachments(toJson(list(body.get("attachments"))));
        msgMapper.insert(userMsg);

        List<String> kbIds = list(body.get("knowledgeBaseIds"));
        String context = buildConvContext(content,
                kbIds.isEmpty() ? fromJsonList(c.getKnowledgeBaseIds()) : kbIds, userId);
        String attached = str(body.get("attachedContext"), "");
        if (!attached.isEmpty()) {
            context = attached + (context.isEmpty() ? "" : "\n\n" + context);
        }
        String reply = aiService.chat(List.of(new AiService.ChatMessage("user", content)), context, model);

        AiMessage asstMsg = new AiMessage();
        asstMsg.setId("msg_" + shortId());
        asstMsg.setConversationId(conversationId);
        asstMsg.setRole("assistant");
        asstMsg.setContent("AI 服务暂时不可用，请稍后重试".equals(reply) ? "AI 服务暂不可用，请稍后重试" : reply);
        asstMsg.setModel(model);
        msgMapper.insert(asstMsg);

        c.setTitle(titleFrom(content, str(c.getTitle(), "New conversation")));
        c.setModel(model);
        convMapper.updateById(c);

        return Map.of("conversation", conversationView(c),
                "userMessage", messageMap(userMsg), "assistantMessage", messageMap(asstMsg));
    }

    @Transactional
    public Map<String, Object> feedback(String messageId, Map<String, Object> body) {
        requireUser();
        AiMessage m = msgMapper.selectById(messageId);
        if (m == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        m.setFeedback(toJson(Map.of("helpful", Boolean.TRUE.equals(body.get("helpful")),
                "reason", str(body.get("reason"), ""), "createdAt", now())));
        msgMapper.updateById(m);
        return messageMap(m);
    }

    // ======================== Internal helpers ========================

    private AiKnowledgeBase requireKb(String id) {
        AiKnowledgeBase k = kbMapper.selectById(id);
        if (k == null || (k.getDeleted() != null && k.getDeleted() == 1))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return k;
    }

    private AiConversation requireConvOwner(String convId, long userId) {
        AiConversation c = convMapper.selectById(convId);
        if (c == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!Objects.equals(c.getOwnerId(), userId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return c;
    }

    private void requireOwner(Long ownerId, long userId) {
        if (!Objects.equals(ownerId, userId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private long requireUser() {
        if (!StpUtil.isLogin()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return StpUtil.getLoginIdAsLong();
    }

    private long userIdOrGuest() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : 0L;
    }

    // ---- entity → Map views (保持与原 JSON 格式一致) ----

    private Map<String, Object> kbView(AiKnowledgeBase k, long userId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", k.getId()); v.put("name", k.getName()); v.put("description", k.getDescription());
        v.put("category", k.getCategory()); v.put("type", k.getType());
        v.put("visibility", k.getVisibility());
        v.put("documentCount", k.getDocumentCount()); v.put("vectorCount", k.getVectorCount());
        v.put("storageBytes", k.getStorageBytes());
        v.put("qaPairCount", k.getQaPairCount() == null ? 0L : k.getQaPairCount());
        v.put("owner", "shared".equals(k.getVisibility()) ? "Shared" : "Mine");
        v.put("isFavorite", favMapper.findTargetIds(userId, "knowledge_base").contains(k.getId()));
        v.put("createdAt", dt(k.getCreatedAt())); v.put("updatedAt", dt(k.getUpdatedAt()));
        return v;
    }

    private Map<String, Object> conversationView(AiConversation c) {
        long msgCount = msgMapper.selectCount(
                new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getConversationId, c.getId()));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.getId()); v.put("title", c.getTitle()); v.put("model", c.getModel());
        v.put("knowledgeBaseIds", fromJsonList(c.getKnowledgeBaseIds()));
        v.put("messageCount", msgCount);
        v.put("createdAt", dt(c.getCreatedAt())); v.put("updatedAt", dt(c.getUpdatedAt()));
        return v;
    }

    private Map<String, Object> messageMap(AiMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId()); v.put("role", m.getRole()); v.put("content", m.getContent());
        v.put("model", m.getModel());
        v.put("knowledgeBaseIds", fromJsonList(m.getKnowledgeBaseIds()));
        v.put("attachments", fromJsonList(m.getAttachments()));
        v.put("feedback", fromJsonMap(m.getFeedback()));
        v.put("createdAt", dt(m.getCreatedAt()));
        return v;
    }

    private Map<String, Object> docMap(AiKbDocument d) {
        return Map.of("id", d.getId(), "fileName", str(d.getFileName(), ""),
                "fileSize", d.getFileSize() == null ? 0L : d.getFileSize(),
                "storageKey", str(d.getStorageKey(), ""),
                "tags", fromJsonList(d.getTags()),
                "parseMode", str(d.getParseMode(), "auto"),
                "status", str(d.getStatus(), "ready"),
                "createdAt", dt(d.getCreatedAt()));
    }

    private Map<String, Object> qaMap(AiKbQaPair qa) {
        return Map.of("id", qa.getId(), "question", str(qa.getQuestion(), ""),
                "answer", str(qa.getAnswer(), ""),
                "tags", fromJsonList(qa.getTags()),
                "createdAt", dt(qa.getCreatedAt()));
    }

    private AiNote requireNote(String id) {
        AiNote note = noteMapper.selectById(id);
        if (note == null || note.getDeleted() != null && note.getDeleted() == 1)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return note;
    }

    private Map<String, Object> noteView(AiNote n, long userId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", n.getId());
        v.put("title", n.getTitle());
        v.put("content", str(n.getContent(), ""));
        v.put("contentType", str(n.getContentType(), "markdown"));
        v.put("tags", fromJsonList(n.getTags()));
        v.put("status", str(n.getStatus(), "draft"));
        // 审核流字段（2026-07-16）：作者端展示驳回原因，管理端展示审核留痕
        v.put("reviewReason", str(n.getReviewReason(), ""));
        v.put("reviewedBy", n.getReviewedBy());
        v.put("reviewedAt", n.getReviewedAt() == null ? null : dt(n.getReviewedAt()));
        v.put("viewCount", n.getViewCount() == null ? 0L : n.getViewCount());
        v.put("ownerId", n.getOwnerId());
        v.put("knowledgeBaseId", str(n.getKnowledgeBaseId(), ""));
        v.put("createdAt", dt(n.getCreatedAt()));
        v.put("updatedAt", dt(n.getUpdatedAt()));
        // 外部同步笔记的出处元数据（空串代表本站原生笔记，前端 v-if 会自动隐藏）
        v.put("sourceUrl",    str(n.getSourceUrl(),    ""));
        v.put("sourceName",   str(n.getSourceName(),   ""));
        v.put("sourceAuthor", str(n.getSourceAuthor(), ""));
        // 作者信息公开展示——使用 raw SQL 绕过 @TableLogic 和租户插件
        if (n.getOwnerId() != null && n.getOwnerId() > 0) {
            Map<String, Object> info = userMapper.selectPublicInfoById(n.getOwnerId());
            if (info != null) {
                v.put("ownerName", String.valueOf(info.getOrDefault("nickname", "未知用户")));
                v.put("ownerAvatar", String.valueOf(info.getOrDefault("avatarUrl", "")));
            } else {
                v.put("ownerName", "未知用户");
                v.put("ownerAvatar", "");
            }
        } else {
            v.put("ownerName", "未知用户");
            v.put("ownerAvatar", "");
        }
        return v;
    }

    // ---- JSON conversion helpers (与旧版 Map-based 存储兼容) ----

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) { return Map.of(); }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) { return null; }
    }

    // ---- Sorting / filtering / paging (保持原逻辑) ----

    private void sortKnowledgeBases(List<Map<String, Object>> list, String sort) {
        if ("docs".equals(sort)) list.sort(Comparator.comparingLong(v -> -num(v.get("documentCount"))));
        else list.sort(Comparator.comparing(v -> str(v.get("updatedAt"), ""), Comparator.reverseOrder()));
    }

    private boolean kbTab(AiKnowledgeBase k, String tab, long userId) {
        if ("mine".equals(tab)) return Objects.equals(k.getOwnerId(), userId);
        if ("shared".equals(tab)) return "shared".equals(k.getVisibility());
        if ("favorite".equals(tab)) return favMapper.findTargetIds(userId, "knowledge_base").contains(k.getId());
        return true;
    }

    private Map<String, Object> page(List<Map<String, Object>> list, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize <= 0 ? 10 : pageSize));
        int from = Math.min(list.size(), (safePage - 1) * safeSize);
        int to = Math.min(list.size(), from + safeSize);
        return Map.of("items", list.subList(from, to), "total", list.size());
    }

    /** 与 admin 内容管理页对齐的 PageResult 分页封装。 */
    private PageResult<Map<String, Object>> pageResult(List<Map<String, Object>> list, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize <= 0 ? 20 : pageSize));
        int from = Math.min(list.size(), (safePage - 1) * safeSize);
        int to = Math.min(list.size(), from + safeSize);
        return PageResult.of(list.subList(from, to), list.size(), safePage, safeSize);
    }

    // ---- Conversation context builder（真实 RAG，2026-07-12 重写）----

    /**
     * 按用户消息检索"挂载知识库的文档切块 + 问答对 + 本人笔记 + 公开教程"，
     * 拼装为注入 prompt 的上下文。检索实现见 {@link KbRagService}。
     * 旧实现只把知识库名称字符串拼进 prompt（占位假 RAG），已废弃。
     */
    private String buildConvContext(String query, List<String> kbIds, long userId) {
        try {
            return kbRagService.retrieveContext(query, kbIds, userId);
        } catch (Exception e) {
            log.warn("RAG retrieve failed, degrading to no context: {}", e.getMessage());
            return "";
        }
    }

    // ======================== OSS helpers for notes & documents ========================

    private String uploadNoteContent(String noteId, String content) {
        try {
            String ossName = "notes/" + noteId + ".md";
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return storageService.upload(
                    new java.io.ByteArrayInputStream(bytes),
                    ossName, "text/markdown; charset=utf-8", bytes.length);
        } catch (Exception e) {
            log.warn("Failed to upload note {} to OSS, falling back to DB: {}", noteId, e.getMessage());
            return null;
        }
    }

    private String resolveNoteContent(AiNote note) {
        if (note.getContentOssKey() != null && !note.getContentOssKey().isBlank()) {
            try {
                java.io.InputStream is = storageService.download(note.getContentOssKey());
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("OSS read failed for note {}, falling back to DB: {}", note.getId(), e.getMessage());
            }
        }
        return str(note.getContent(), "");
    }

    private void deleteNoteOssContent(AiNote note) {
        if (note.getContentOssKey() != null && !note.getContentOssKey().isBlank()) {
            try {
                storageService.delete(note.getContentOssKey());
            } catch (Exception e) {
                log.warn("Failed to delete OSS object for note {}: {}", note.getId(), e.getMessage());
            }
        }
    }

    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource>
            downloadDocument(String knowledgeBaseId, String documentId) {
        requireKb(knowledgeBaseId);
        AiKbDocument doc = docMapper.selectById(documentId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(knowledgeBaseId))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (doc.getStorageKey() == null || doc.getStorageKey().isBlank())
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "文档文件尚未上传");
        try {
            java.io.InputStream is = storageService.download(doc.getStorageKey());
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" +
                            java.net.URLEncoder.encode(doc.getFileName(), java.nio.charset.StandardCharsets.UTF_8) + "\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .body(new org.springframework.core.io.InputStreamResource(is));
        } catch (Exception e) {
            log.warn("Failed to download KB document {}: {}", documentId, e.getMessage());
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    // ---- Simple value helpers ----

    private String titleFrom(String content, String fallback) {
        if (blank(content)) return fallback;
        String v = content.strip();
        return v.length() > 24 ? v.substring(0, 24) : v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        if (value instanceof List<?> raw) return raw.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        if (value instanceof String s && !s.isBlank()) return List.of(s);
        return List.of();
    }

    private boolean matchesStr(String name, String desc, String cat, String keyword) {
        if (blank(keyword)) return true;
        String q = keyword.toLowerCase(Locale.ROOT);
        return str(name, "").toLowerCase(Locale.ROOT).contains(q)
                || str(desc, "").toLowerCase(Locale.ROOT).contains(q)
                || str(cat, "").toLowerCase(Locale.ROOT).contains(q);
    }

    private String str(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private long num(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private double dbl(Object value) { return value instanceof Number n ? n.doubleValue() : 0.0; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private String now() { return OffsetDateTime.now().toString(); }
    private String dt(java.time.LocalDateTime t) { return t == null ? now() : t.toString(); }

    // ---- Reflection-based entity patching (替代原 Map patch) ----
    private void patchEntity(Object entity, Map<String, Object> body, String... fields) {
        for (String field : fields) {
            if (!body.containsKey(field)) continue;
            try {
                var setter = entity.getClass().getMethod("set" + Character.toUpperCase(field.charAt(0)) + field.substring(1), String.class);
                setter.invoke(entity, str(body.get(field), null));
            } catch (Exception ignored) {}
        }
    }
}
