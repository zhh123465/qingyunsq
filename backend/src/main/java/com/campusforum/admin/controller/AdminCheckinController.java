package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.BatchStatusRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.checkin.dto.CheckinChallengeVO;
import com.campusforum.checkin.service.CheckinService;
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
 * 打卡挑战管理（管理端）。
 * checkin_challenges 表没有 deleted 字段，语义借用 status：1 正常 / 2 隐藏 / 0 回收站。
 */
@RestController
@RequestMapping("/api/v1/admin/checkin/challenges")
@RequiredArgsConstructor
public class AdminCheckinController {

    private final CheckinService checkinService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:checkin:manage")
    public R<PageResult<CheckinChallengeVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<CheckinChallengeVO> ipage;
        if (Boolean.TRUE.equals(trash)) {
            ipage = checkinService.listTrashForAdminPaged(keyword, page, size);
        } else {
            ipage = checkinService.listForAdminPaged(keyword, status, page, size);
        }
        return R.ok(PageResult.of(ipage));
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer s = body.get("status");
        checkinService.setStatusForAdmin(id, s);
        auditLogService.log("CHECKIN_STATUS", "checkin_challenge", id,
                "checkin status set to " + s + " by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Void> forceDelete(@PathVariable Long id) {
        checkinService.deleteByAdmin(id);
        auditLogService.log("CHECKIN_FORCE_DELETE", "checkin_challenge", id,
                "checkin force deleted by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Void> restore(@PathVariable Long id) {
        checkinService.restoreForAdmin(id);
        auditLogService.log("CHECKIN_RESTORE", "checkin_challenge", id,
                "checkin restored from trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = checkinService.purgeForAdmin(id);
        auditLogService.log("CHECKIN_PURGE", "checkin_challenge", id,
                "checkin purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascaded " + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchStatusRequest req) {
        int s = req.getStatus();
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = checkinService.setStatusBatchForAdmin(new ArrayList<>(ids), s);
        auditLogService.log("CHECKIN_BATCH_STATUS", "checkin_challenge", null,
                "batch status=" + s + " on " + ids.size() + " challenges by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = checkinService.deleteBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("CHECKIN_BATCH_DELETE", "checkin_challenge", null,
                "batch delete " + ids.size() + " challenges by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = checkinService.restoreBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("CHECKIN_BATCH_RESTORE", "checkin_challenge", null,
                "batch restore " + ids.size() + " challenges by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:checkin:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = checkinService.purgeBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("CHECKIN_BATCH_PURGE", "checkin_challenge", null,
                "batch purge " + ids.size() + " challenges by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
