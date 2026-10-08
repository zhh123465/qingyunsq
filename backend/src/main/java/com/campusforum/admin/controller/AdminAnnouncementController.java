package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.admin.dto.BatchAnnouncementStatusRequest;
import com.campusforum.admin.dto.BatchIdsRequest;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.announcement.dto.AnnouncementCreateRequest;
import com.campusforum.announcement.dto.AnnouncementUpdateRequest;
import com.campusforum.announcement.dto.AnnouncementVO;
import com.campusforum.announcement.service.AnnouncementService;
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
 * 公告管理（TENANT_ADMIN / SUPER_ADMIN）。
 *
 * <p>权限 {@code tenant:announcement:manage}；SUPER_ADMIN 通过继承生效。
 * 租户隔离由 MyBatis-Plus 插件自动完成。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;
    private final AuditLogService auditLogService;

    @GetMapping
    @SaCheckPermission("tenant:announcement:manage")
    public R<PageResult<AnnouncementVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean trash,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(announcementService.pageForAdmin(keyword, status, level, trash, page, size));
    }

    @PostMapping
    @SaCheckPermission("tenant:announcement:manage")
    public R<AnnouncementVO> create(@Valid @RequestBody AnnouncementCreateRequest req) {
        AnnouncementVO vo = announcementService.createByAdmin(req);
        auditLogService.log("ANNOUNCEMENT_CREATE", "announcement", vo.getId(),
                "announcement created by admin " + StpUtil.getLoginIdAsLong()
                        + "; title=" + vo.getTitle() + " status=" + vo.getStatus());
        return R.ok(vo);
    }

    @PutMapping("/{id}")
    @SaCheckPermission("tenant:announcement:manage")
    public R<AnnouncementVO> update(@PathVariable Long id,
                                    @Valid @RequestBody AnnouncementUpdateRequest req) {
        AnnouncementVO vo = announcementService.updateByAdmin(id, req);
        auditLogService.log("ANNOUNCEMENT_UPDATE", "announcement", id,
                "announcement updated by admin " + StpUtil.getLoginIdAsLong());
        return R.ok(vo);
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Void> setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String s = body.get("status");
        announcementService.setStatus(id, s);
        auditLogService.log("ANNOUNCEMENT_STATUS", "announcement", id,
                "announcement " + id + " status set to " + s + " by admin "
                        + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/pin")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Integer> togglePin(@PathVariable Long id) {
        Integer next = announcementService.togglePin(id);
        auditLogService.log("ANNOUNCEMENT_PIN", "announcement", id,
                "announcement " + id + " pin toggled to " + next + " by admin "
                        + StpUtil.getLoginIdAsLong());
        return R.ok(next);
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Void> forceDelete(@PathVariable Long id) {
        announcementService.deleteByAdmin(id);
        auditLogService.log("ANNOUNCEMENT_FORCE_DELETE", "announcement", id,
                "announcement " + id + " deleted to trash by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @PutMapping("/{id}/restore")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Void> restore(@PathVariable Long id) {
        announcementService.restoreByAdmin(id);
        auditLogService.log("ANNOUNCEMENT_RESTORE", "announcement", id,
                "announcement " + id + " restored by admin " + StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    @DeleteMapping("/{id}/purge")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Map<String, Integer>> purge(@PathVariable Long id) {
        Map<String, Integer> counts = announcementService.purgeByAdmin(id);
        auditLogService.log("ANNOUNCEMENT_PURGE", "announcement", id,
                "announcement " + id + " purged by admin " + StpUtil.getLoginIdAsLong()
                        + "; cascade=" + counts);
        return R.ok(counts);
    }

    // === 批量 ===

    @PutMapping("/batch-status")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Integer> batchSetStatus(@Valid @RequestBody BatchAnnouncementStatusRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = announcementService.setStatusBatchForAdmin(new ArrayList<>(ids), req.getStatus());
        auditLogService.log("ANNOUNCEMENT_BATCH_STATUS", "announcement", null,
                "batch status=" + req.getStatus() + " on " + ids.size()
                        + " announcements by admin " + StpUtil.getLoginIdAsLong()
                        + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Integer> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = announcementService.deleteBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("ANNOUNCEMENT_BATCH_DELETE", "announcement", null,
                "batch delete " + ids.size() + " announcements by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @PutMapping("/batch-restore")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Integer> batchRestore(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int rows = announcementService.restoreBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("ANNOUNCEMENT_BATCH_RESTORE", "announcement", null,
                "batch restore " + ids.size() + " announcements by admin "
                        + StpUtil.getLoginIdAsLong() + "; affected=" + rows);
        return R.ok(rows);
    }

    @DeleteMapping("/batch-purge")
    @SaCheckPermission("tenant:announcement:manage")
    public R<Map<String, Object>> batchPurge(@Valid @RequestBody BatchIdsRequest req) {
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        Map<String, Object> result = announcementService.purgeBatchForAdmin(new ArrayList<>(ids));
        auditLogService.log("ANNOUNCEMENT_BATCH_PURGE", "announcement", null,
                "batch purge " + ids.size() + " announcements by admin "
                        + StpUtil.getLoginIdAsLong() + "; success=" + result.get("success")
                        + " failed=" + ((List<?>) result.get("failed")).size()
                        + " cascade=" + result.get("counts"));
        return R.ok(result);
    }
}
