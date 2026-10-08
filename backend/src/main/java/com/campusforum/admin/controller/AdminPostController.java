package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.BatchStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.common.R;
import com.campusforum.post.dto.PostVO;
import com.campusforum.post.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:post:manage")
    public R<PageResult<PostVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // trash=true 走回收站视图（deleted=1），其他分支走正常列表
        IPage<PostVO> ipage;
        if (Boolean.TRUE.equals(trash)) {
            ipage = postService.listTrashForAdminPaged(keyword, scope, page, size);
        } else {
            ipage = postService.listPostsForAdminPaged(keyword, status, scope, page, size);
        }
        return R.ok(PageResult.of(ipage));
    }

    @PutMapping("/{id}/pin")
    @SaCheckPermission("tenant:post:manage")
    public R<Void> togglePin(@PathVariable Long id) {
        postService.togglePin(id);
        auditLogService.log("POST_PIN", "post", id,
                "post pin toggled by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/essence")
    @SaCheckPermission("tenant:post:manage")
    public R<Void> toggleEssence(@PathVariable Long id) {
        postService.toggleEssence(id);
        auditLogService.log("POST_ESSENCE", "post", id,
                "post essence toggled by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:post:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer newStatus = body.get("status");
        postService.setStatus(id, newStatus);
        auditLogService.log("POST_STATUS", "post", id,
                "post status set to " + newStatus + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:post:manage")
    public R<Void> forceDelete(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        postService.deletePost(userId, id);
        auditLogService.log("POST_FORCE_DELETE", "post", id,
                "post force deleted by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    // === 回收站 ===
    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:post:manage")
    public R<Void> restore(@PathVariable Long id) {
        postService.restoreForAdmin(id);
        auditLogService.log("POST_RESTORE", "post", id,
                "post restored from trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:post:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = postService.purgeForAdmin(id);
        auditLogService.log("POST_PURGE", "post", id,
                "post purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量操作 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:post:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchStatusRequest req) {
        // Post 允许 status ∈ {0,1,2}
        int s = req.getStatus();
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = postService.setStatusBatchForAdmin(new java.util.ArrayList<>(ids), s);
        auditLogService.log("POST_BATCH_STATUS", "post", null,
                "batch status=" + s + " on " + ids.size() + " posts by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:post:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = postService.deleteBatchForAdmin(new java.util.ArrayList<>(ids));
        auditLogService.log("POST_BATCH_DELETE", "post", null,
                "batch delete " + ids.size() + " posts by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:post:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = postService.restoreBatchForAdmin(new java.util.ArrayList<>(ids));
        auditLogService.log("POST_BATCH_RESTORE", "post", null,
                "batch restore " + ids.size() + " posts by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:post:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = postService.purgeBatchForAdmin(new java.util.ArrayList<>(ids));
        auditLogService.log("POST_BATCH_PURGE", "post", null,
                "batch purge " + ids.size() + " posts by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
