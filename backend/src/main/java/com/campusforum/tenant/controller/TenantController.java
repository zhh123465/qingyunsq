package com.campusforum.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.common.R;
import com.campusforum.tenant.TenantContext;
import com.campusforum.tenant.audit.TenantAuditService;
import com.campusforum.tenant.domain.Tenant;
import com.campusforum.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final TenantAuditService auditService;

    @GetMapping
    @SaCheckPermission("super:tenant:manage")
    public R<List<Tenant>> list(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Long cursor,
                                 @RequestParam(defaultValue = "20") int limit) {
        return R.ok(tenantService.listAll(keyword, cursor, limit));
    }

    @PostMapping
    @SaCheckPermission("super:tenant:manage")
    public R<Tenant> create(@RequestBody Map<String, String> body) {
        Tenant tenant = tenantService.create(
                body.get("code"),
                body.get("name"),
                body.get("domain"));
        return R.ok(tenant);
    }

    @PutMapping("/{id}")
    @SaCheckPermission("super:tenant:manage")
    public R<Tenant> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(tenantService.update(id,
                body.get("name"),
                body.get("domain"),
                body.get("logoUrl"),
                body.get("announcement")));
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("super:tenant:manage")
    public R<Void> toggleStatus(@PathVariable Long id) {
        tenantService.toggleStatus(id);
        return R.ok();
    }

    @GetMapping("/{id}/ai-config")
    @SaCheckPermission("tenant:self:ai-config")
    public R<Map<String, Object>> getAiConfig(@PathVariable Long id) {
        assertSameTenantOrSuper(id);
        Map<String, Object> cfg = new LinkedHashMap<>(tenantService.getAiConfig(id));
        // 敏感字段 apiKey 永远不以明文形式离开后端，前端只能看到掩码视图。
        if (cfg.get("apiKey") instanceof String s) {
            cfg.put("apiKey", maskApiKey(s));
        }
        return R.ok(cfg);
    }

    @PutMapping("/{id}/ai-config")
    @SaCheckPermission("tenant:self:ai-config")
    public R<Void> updateAiConfig(@PathVariable Long id, @RequestBody Map<String, String> body,
                                  HttpServletRequest req) {
        assertSameTenantOrSuper(id);
        String provider = body.get("provider");
        String baseUrl = body.get("baseUrl");
        String apiKey = body.get("apiKey");
        String model = body.get("model");

        // GET 接口返回的是 apiKey 的掩码（含 "***"）。如果前端表单未修改 apiKey、原样回传掩码，
        // 这里识别为「用户未修改」，传 null 让 service 跳过该字段更新，避免真实 key 被覆盖成掩码字符串。
        String apiKeyForUpdate = (apiKey != null && apiKey.contains("***")) ? null : apiKey;

        // F6: 先 GET 旧 cfg（解密后），用于在 controller 层算 diff —— 只读 DB 一次。
        Map<String, Object> before = tenantService.getAiConfig(id);

        tenantService.updateAiConfig(id, provider, baseUrl, apiKeyForUpdate, model);

        // 计算「实际生效的新值」：partial update 语义 — body 中 null 字段保留旧值；apiKey 占位符也保留旧值
        Map<String, Object> effectiveNew = new LinkedHashMap<>(before);
        if (provider != null) effectiveNew.put("provider", provider);
        if (baseUrl != null)  effectiveNew.put("baseUrl", baseUrl);
        if (model != null)    effectiveNew.put("model", model);
        if (apiKeyForUpdate != null) effectiveNew.put("apiKey", apiKeyForUpdate);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changes", buildDiff(before, effectiveNew));
        // F7: 先在 controller 线程中提取 IP（req 在异步任务执行时可能已被 Tomcat 回收）
        String ipAddress = TenantAuditService.resolveClientIp(req);
        auditService.recordAdminAction(StpUtil.getLoginIdAsLong(), id, ipAddress,
                "AI_CONFIG_UPDATE", "TENANT", id, detail);
        return R.ok();
    }

    /**
     * 对比新旧 AI 配置，只返回变化的字段。apiKey 用掩码后比较，保证 audit 永不存原始 key。
     */
    private static Map<String, Object> buildDiff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : List.of("provider", "baseUrl", "model", "apiKey")) {
            Object oldVal = before.get(field);
            Object newVal = after.get(field);
            String oldStr = "apiKey".equals(field) ? maskApiKey(toStringOrEmpty(oldVal)) : toStringOrEmpty(oldVal);
            String newStr = "apiKey".equals(field) ? maskApiKey(toStringOrEmpty(newVal)) : toStringOrEmpty(newVal);
            if (!oldStr.equals(newStr)) {
                Map<String, String> change = new LinkedHashMap<>();
                change.put("from", oldStr);
                change.put("to", newStr);
                changes.put(field, change);
            }
        }
        return changes;
    }

    private static String toStringOrEmpty(Object value) {
        return value instanceof String s ? s : (value == null ? "" : value.toString());
    }

    private static String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "<empty>";
        if (key.length() < 8) return "*".repeat(key.length());
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    /**
     * 权限扩容：TENANT_ADMIN 允许管理自己租户的 AI 配置，但不能跨租户。
     * 仅当路径 id 与当前 TenantContext 一致，或当前是 SUPER_ADMIN，才允许通过。
     */
    private void assertSameTenantOrSuper(Long pathTenantId) {
        if (StpUtil.hasPermission("super:tenant:manage")) {
            return;
        }
        Long current = TenantContext.getTenantId();
        if (current == null || !current.equals(pathTenantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(),
                    "无权管理其他租户的 AI 配置");
        }
    }
}
