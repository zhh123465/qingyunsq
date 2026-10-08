package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campusforum.admin.dto.AdminCommentVO;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.BatchStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.common.R;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.post.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentService commentService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:comment:manage")
    public R<PageResult<AdminCommentVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long postId,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<AdminCommentVO> ipage;
        if (Boolean.TRUE.equals(trash)) {
            ipage = commentService.listTrashForAdminPaged(keyword, postId, authorId, page, size);
        } else {
            ipage = commentService.listForAdminPaged(keyword, postId, authorId, status, page, size);
        }
        return R.ok(PageResult.of(ipage));
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:comment:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer s = body.get("status");
        commentService.setStatusForAdmin(id, s);
        auditLogService.log("COMMENT_STATUS", "comment", id,
                "comment status set to " + s + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:comment:manage")
    public R<Void> forceDelete(@PathVariable Long id) {
        commentService.deleteByAdmin(id);
        auditLogService.log("COMMENT_FORCE_DELETE", "comment", id,
                "comment force deleted by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:comment:manage")
    public R<Void> restore(@PathVariable Long id) {
        commentService.restoreForAdmin(id);
        auditLogService.log("COMMENT_RESTORE", "comment", id,
                "comment restored from trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:comment:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = commentService.purgeForAdmin(id);
        auditLogService.log("COMMENT_PURGE", "comment", id,
                "comment purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:comment:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchStatusRequest req) {
        int s = req.getStatus();
        if (s != 0 && s != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值（仅允许 0 或 1）");
        }
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = commentService.setStatusBatchForAdmin(new ArrayList<>(ids), s);
        auditLogService.log("COMMENT_BATCH_STATUS", "comment", null,
                "batch status=" + s + " on " + ids.size() + " comments by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:comment:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = commentService.deleteBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("COMMENT_BATCH_DELETE", "comment", null,
                "batch delete " + ids.size() + " comments by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:comment:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = commentService.restoreBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("COMMENT_BATCH_RESTORE", "comment", null,
                "batch restore " + ids.size() + " comments by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:comment:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = commentService.purgeBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("COMMENT_BATCH_PURGE", "comment", null,
                "batch purge " + ids.size() + " comments by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
