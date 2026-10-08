package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.admin.dto.BatchNoteIdsRequest;
import com.campusforum.admin.dto.BatchNoteStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.admin.dto.SyncExternalRequest;
import com.campusforum.ai.workspace.AiWorkspaceService;
import com.campusforum.ai.workspace.service.NoteSyncService;
import com.campusforum.common.R;
import com.campusforum.infra.audit.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 笔记管理（管理端）。ai_notes.status 是 VARCHAR，管理端语义：
 *  - draft：草稿（前台看不到）
 *  - pending：待审核（审核流 2026-07-16，普通用户点"发布"落此状态，前台看不到）
 *  - published：已发布（前台学习页可见）
 *  - rejected：已驳回（审核流 2026-07-16，review_reason 存原因，前台看不到）
 *  - hidden：管理员隐藏（前台看不到）
 *  - deleted=1：回收站
 */
@RestController
@RequestMapping("/api/v1/admin/notes")
@RequiredArgsConstructor
public class AdminNoteController {

    private final AiWorkspaceService aiWorkspaceService;
    private final AuditLogService auditLogService;
    private final NoteSyncService noteSyncService;

    @GetMapping
    @SaCheckPermission("tenant:note:manage")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<Map<String, Object>> res;
        if (Boolean.TRUE.equals(trash)) {
            res = aiWorkspaceService.adminListNotesTrash(keyword, ownerId, page, size);
        } else {
            res = aiWorkspaceService.adminListNotes(keyword, status, ownerId, page, size);
        }
        return R.ok(res);
    }

    // === 审核流（2026-07-16）：通过 / 驳回 / 批量通过，照 AdminResourceController 同款模式 ===

    @PutMapping("/{id}/approve")
    @SaCheckPermission("tenant:note:manage")
    public R<Void> approve(@PathVariable String id) {
        long adminId = StpUtil.getLoginIdAsLong();
        aiWorkspaceService.adminApproveNote(id, adminId);
        auditLogService.log("NOTE_APPROVE", "ai_note", null,
                "note " + id + " approved by admin " + adminId);
        return R.ok();
    }

    @PutMapping("/{id}/reject")
    @SaCheckPermission("tenant:note:manage")
    public R<Void> reject(@PathVariable String id, @RequestBody Map<String, String> body) {
        long adminId = StpUtil.getLoginIdAsLong();
        String reason = body.get("reason");
        aiWorkspaceService.adminRejectNote(id, adminId, reason);
        auditLogService.log("NOTE_REJECT", "ai_note", null,
                "note " + id + " rejected by admin " + adminId + "; reason=" + reason);
        return R.ok();
    }

    @PutMapping("/batch-approve")
    @SaCheckPermission("tenant:note:manage")
    public R<Map<String, Object>> batchApprove(@Valid @RequestBody BatchNoteIdsRequest req) {
        long adminId = StpUtil.getLoginIdAsLong();
        Set<String> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = aiWorkspaceService.adminApproveNoteBatch(new ArrayList<>(ids), adminId);
        auditLogService.log("NOTE_BATCH_APPROVE", "ai_note", null,
                "batch approve " + ids.size() + " notes by admin " + adminId
                        + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size());
        return R.ok(result);
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:note:manage")
    public R<Void> setStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String s = body.get("status");
        aiWorkspaceService.adminSetNoteStatus(id, s);
        auditLogService.log("NOTE_STATUS", "ai_note", null,
                "note " + id + " status set to " + s + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:note:manage")
    public R<Void> forceDelete(@PathVariable String id) {
        aiWorkspaceService.adminDeleteNote(id);
        auditLogService.log("NOTE_FORCE_DELETE", "ai_note", null,
                "note " + id + " force deleted by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:note:manage")
    public R<Void> restore(@PathVariable String id) {
        aiWorkspaceService.adminRestoreNote(id);
        auditLogService.log("NOTE_RESTORE", "ai_note", null,
                "note " + id + " restored by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:note:manage")
    public R<Map<String, Integer>> purge(@PathVariable String id) {
        Map<String, Integer> counts = aiWorkspaceService.adminPurgeNote(id);
        auditLogService.log("NOTE_PURGE", "ai_note", null,
                "note " + id + " purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:note:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchNoteStatusRequest req) {
        Set<String> ids = new LinkedHashSet<>(req.getIds());
        int rows = aiWorkspaceService.adminSetNoteStatusBatch(new ArrayList<>(ids), req.getStatus());
        auditLogService.log("NOTE_BATCH_STATUS", "ai_note", null,
                "batch status=" + req.getStatus() + " on " + ids.size() + " notes by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:note:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchNoteIdsRequest req) {
        Set<String> ids = new LinkedHashSet<>(req.getIds());
        int rows = aiWorkspaceService.adminDeleteNoteBatch(new ArrayList<>(ids));
        auditLogService.log("NOTE_BATCH_DELETE", "ai_note", null,
                "batch delete " + ids.size() + " notes by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:note:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchNoteIdsRequest req) {
        Set<String> ids = new LinkedHashSet<>(req.getIds());
        int rows = aiWorkspaceService.adminRestoreNoteBatch(new ArrayList<>(ids));
        auditLogService.log("NOTE_BATCH_RESTORE", "ai_note", null,
                "batch restore " + ids.size() + " notes by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:note:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchNoteIdsRequest req) {
        Set<String> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = aiWorkspaceService.adminPurgeNoteBatch(new ArrayList<>(ids));
        auditLogService.log("NOTE_BATCH_PURGE", "ai_note", null,
                "batch purge " + ids.size() + " notes by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }

    /**
     * 同步外部（同学的）公开笔记：抓取 → HTML→Markdown → 存 ai_notes（status=published）。
     * 三种模式：single 单页；vitepress 目录（可 recursive）；cnblogs 用户主页遍历分页。
     */
    @PostMapping("/sync-external")
    @SaCheckPermission("tenant:note:manage")
    public R<Map<String, Object>> syncExternal(@Valid @RequestBody SyncExternalRequest req) {
        Map<String, Object> result = noteSyncService.sync(req);
        auditLogService.log("NOTE_SYNC_EXTERNAL", "ai_note", null,
                "sync source=" + req.getSource() + " root=" + req.getRootUrl()
                        + " author=" + req.getSourceAuthor()
                        + " -> synced=" + result.get("synced")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok(result);
    }
}
