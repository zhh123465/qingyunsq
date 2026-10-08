package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.BatchStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.common.R;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.resource.dto.ResourceVO;
import com.campusforum.resource.service.ResourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源管理（管理端）。用户上传的文件资源允许管理员：
 *  - 列表 / 关键字 / 可见性 / 状态 筛选（分页）
 *  - 隐藏（status=0） / 恢复（status=1）
 *  - 删除到回收站（deleted=1）
 *  - 从回收站恢复
 *  - 彻底删除（同时清理 OSS 文件 + 相关点赞收藏）
 *  - 4 种批量操作
 */
@RestController
@RequestMapping("/api/v1/admin/resources")
@RequiredArgsConstructor
public class AdminResourceController {

    private final ResourceService resourceService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:resource:manage")
    public R<PageResult<ResourceVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<ResourceVO> ipage;
        if (Boolean.TRUE.equals(trash)) {
            ipage = resourceService.listTrashForAdminPaged(keyword, page, size);
        } else {
            ipage = resourceService.listForAdminPaged(keyword, visibility, status, page, size);
        }
        return R.ok(PageResult.of(ipage));
    }

    // === 审核流（2026-07-13）：通过 / 驳回 / 批量通过 ===

    @PutMapping("/{id}/approve")
    @SaCheckPermission("tenant:resource:manage")
    public R<Void> approve(@PathVariable Long id) {
        long adminId = StpUtil.getLoginIdAsLong();
        resourceService.approve(id, adminId);
        auditLogService.log("RESOURCE_APPROVE", "resource", id,
                "resource approved by admin " + adminId);
        return R.ok();
    }

    @PutMapping("/{id}/reject")
    @SaCheckPermission("tenant:resource:manage")
    public R<Void> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        long adminId = StpUtil.getLoginIdAsLong();
        String reason = body.get("reason");
        resourceService.reject(id, adminId, reason);
        auditLogService.log("RESOURCE_REJECT", "resource", id,
                "resource rejected by admin " + adminId + "; reason=" + reason);
        return R.ok();
    }

    @PutMapping("/batch-approve")
    @SaCheckPermission("tenant:resource:manage")
    public R<Map<String, Object>> batchApprove(@Valid @RequestBody BatchIdsRequest req) {
        long adminId = StpUtil.getLoginIdAsLong();
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = resourceService.approveBatch(new ArrayList<>(ids), adminId);
        auditLogService.log("RESOURCE_BATCH_APPROVE", "resource", null,
                "batch approve " + ids.size() + " resources by admin " + adminId
                        + "; success=" + result.get("success"));
        return R.ok(result);
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:resource:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer s = body.get("status");
        resourceService.setStatusForAdmin(id, s);
        auditLogService.log("RESOURCE_STATUS", "resource", id,
                "resource status set to " + s + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:resource:manage")
    public R<Void> forceDelete(@PathVariable Long id) {
        resourceService.deleteByAdmin(id);
        auditLogService.log("RESOURCE_FORCE_DELETE", "resource", id,
                "resource force deleted by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:resource:manage")
    public R<Void> restore(@PathVariable Long id) {
        resourceService.restoreForAdmin(id);
        auditLogService.log("RESOURCE_RESTORE", "resource", id,
                "resource restored from trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:resource:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = resourceService.purgeForAdmin(id);
        auditLogService.log("RESOURCE_PURGE", "resource", id,
                "resource purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:resource:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchStatusRequest req) {
        int s = req.getStatus();
        if (s != 0 && s != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值（仅允许 0 或 1）");
        }
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = resourceService.setStatusBatchForAdmin(new ArrayList<>(ids), s);
        auditLogService.log("RESOURCE_BATCH_STATUS", "resource", null,
                "batch status=" + s + " on " + ids.size() + " resources by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:resource:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = resourceService.deleteBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("RESOURCE_BATCH_DELETE", "resource", null,
                "batch delete " + ids.size() + " resources by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:resource:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = resourceService.restoreBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("RESOURCE_BATCH_RESTORE", "resource", null,
                "batch restore " + ids.size() + " resources by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:resource:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = resourceService.purgeBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("RESOURCE_BATCH_PURGE", "resource", null,
                "batch purge " + ids.size() + " resources by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
