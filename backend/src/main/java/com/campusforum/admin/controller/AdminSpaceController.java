package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.BatchStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.common.R;
import com.campusforum.space.dto.SpaceVO;
import com.campusforum.space.service.SpaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/spaces")
@RequiredArgsConstructor
public class AdminSpaceController {

    private final SpaceService spaceService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:space:manage")
    public R<PageResult<SpaceVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<SpaceVO> ipage;
        if (Boolean.TRUE.equals(trash)) {
            ipage = spaceService.listTrashForAdminPaged(keyword, category, page, size);
        } else {
            ipage = spaceService.listSpacesForAdminPaged(keyword, category, status, page, size);
        }
        return R.ok(PageResult.of(ipage));
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:space:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer newStatus = body.get("status");
        spaceService.setStatus(id, newStatus);
        auditLogService.log("SPACE_STATUS", "space", id,
                "space status set to " + newStatus + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:space:manage")
    public R<Void> dismiss(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        spaceService.dismiss(id, userId);
        auditLogService.log("SPACE_DISMISS", "space", id,
                "space dismissed by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    // === 回收站 ===
    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:space:manage")
    public R<Void> restore(@PathVariable Long id) {
        spaceService.restoreForAdmin(id);
        auditLogService.log("SPACE_RESTORE", "space", id,
                "space restored from trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:space:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = spaceService.purgeForAdmin(id);
        auditLogService.log("SPACE_PURGE", "space", id,
                "space purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:space:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchStatusRequest req) {
        int s = req.getStatus();
        if (s != 0 && s != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值（仅允许 0 或 1）");
        }
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = spaceService.setStatusBatchForAdmin(new ArrayList<>(ids), s);
        auditLogService.log("SPACE_BATCH_STATUS", "space", null,
                "batch status=" + s + " on " + ids.size() + " spaces by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:space:manage")
    public R<Integer> batchDismiss(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = spaceService.deleteBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("SPACE_BATCH_DISMISS", "space", null,
                "batch dismiss " + ids.size() + " spaces by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:space:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = spaceService.restoreBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("SPACE_BATCH_RESTORE", "space", null,
                "batch restore " + ids.size() + " spaces by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:space:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = spaceService.purgeBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("SPACE_BATCH_PURGE", "space", null,
                "batch purge " + ids.size() + " spaces by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
