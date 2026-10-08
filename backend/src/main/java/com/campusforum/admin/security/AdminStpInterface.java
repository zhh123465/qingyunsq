package com.campusforum.admin.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AdminStpInterface implements StpInterface {

    private final UserMapper userMapper;

    /**
     * 租户管理员权限清单。
     *
     * <p>导出权限拆分（任务 T8.6 / 漏洞 13）：原先所有导出端点共用 {@code tenant:dashboard}
     * 这一笼统权限，导致只要能进后台的任意管理员都能拉走全量用户/审计/举报数据。
     * 拆为 4 个细粒度权限后，运维可以按需授权（例如只给数据分析岗 {@code tenant:export:posts}，
     * 不给 {@code tenant:export:audit}）。完整 PII（不脱敏）需要 SUPER_ADMIN 才能调用，
     * 由 {@code ExportController} 在路径内部做二次校验。</p>
     */
    private static final List<String> TENANT_ADMIN_PERMISSIONS = List.of(
            "tenant:dashboard",
            "tenant:user:list",
            "tenant:user:ban",
            "tenant:user:role",
            "tenant:post:manage",
            "tenant:space:manage",
            "tenant:audit:log",
            "tenant:report:manage",
            "tenant:sensitive:manage",
            // === 新增业务实体管理权限（管理端优化）：资源 / 笔记 / 打卡 / 评论 ===
            "tenant:resource:manage",
            "tenant:note:manage",
            "tenant:checkin:manage",
            "tenant:comment:manage",
            // === 学习页排序管理（管理员在学习页直接调整教程/公开笔记展示顺序） ===
            "tenant:learning:manage",
            // === 系统公告：管理员发布本租户公告（前台顶栏横幅+独立公告页展示） ===
            "tenant:announcement:manage",
            // === 本租户 AI 配置：允许 TENANT_ADMIN 管理自己的 AI Provider，
            //     但不能跨租户；跨租户仍需 super:tenant:manage。
            "tenant:self:ai-config",
            // === 导出端点细粒度权限（T8.6） ===
            "tenant:export:users",
            "tenant:export:posts",
            "tenant:export:audit",
            "tenant:export:reports"
    );

    private static final List<String> SUPER_ADMIN_PERMISSIONS;

    static {
        List<String> all = new ArrayList<>(TENANT_ADMIN_PERMISSIONS);
        all.add("super:tenant:manage");
        SUPER_ADMIN_PERMISSIONS = Collections.unmodifiableList(all);
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String role = getRole(loginId);
        if ("SUPER_ADMIN".equals(role)) return SUPER_ADMIN_PERMISSIONS;
        if ("TENANT_ADMIN".equals(role)) return TENANT_ADMIN_PERMISSIONS;
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        String role = getRole(loginId);
        return role != null ? List.of(role) : List.of();
    }

    private String getRole(Object loginId) {
        try {
            SaSession session = StpUtil.getSessionByLoginId(loginId);
            String role = (String) session.get("role");
            if (role != null) return role;
        } catch (Exception ignored) {
        }
        User user = userMapper.selectById(Long.parseLong(loginId.toString()));
        return user != null ? user.getRole() : null;
    }
}
