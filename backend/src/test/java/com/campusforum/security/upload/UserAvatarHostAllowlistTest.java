package com.campusforum.security.upload;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.common.BusinessException;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.LoginLockoutService;
import com.campusforum.infra.security.SecurityProperties;
import com.campusforum.infra.security.TrustedProxyResolver;
import com.campusforum.tenant.cache.ActiveTenantCache;
import com.campusforum.user.config.StudentNoMappingProperties;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.UpdateProfileRequest;
import com.campusforum.user.mapper.UserMapper;
import com.campusforum.user.service.EmailVerificationCodeService;
import com.campusforum.user.service.UserService;
import com.campusforum.wechat.service.WechatMiniProgramClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.campusforum.ai.workspace.mapper.AiKnowledgeBaseMapper;
import com.campusforum.ai.workspace.mapper.AiKbQaPairMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 单元测试：{@link UserService#updateProfile} 的头像 / 封面 URL 白名单校验
 * （T4.6 改造后从 self-hosts 推导 + 空名单语义反转）。
 *
 * <p>对应任务 T4.6，关联 bugfix.md 漏洞 15（profile 资产 host 校验：空白名单语义反转
 * 为"仅本站存储域名"）。</p>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>不启动 Spring 容器，使用 Mockito 构造 {@link UserService}；</li>
 *   <li>使用真实 {@link SecurityProperties} 实例，按用例修改其 upload.allowedAssetHosts /
 *       upload.selfHosts，覆盖各类合并语义；</li>
 *   <li>通过 {@code mockStatic(StpUtil)} 让 ensureCallerWeightSufficient 等
 *       涉及 Sa-Token Session 的代码路径不会被本测试触达 — 但本测试只覆盖
 *       {@code updateProfile} 路径，不涉及那些方法。</li>
 * </ul>
 *
 * <p>覆盖以下行为：</p>
 * <ol>
 *   <li>合并白名单（allowedAssetHosts ∪ selfHosts）为空 → 抛 BAD_REQUEST("未配置允许的资产域名")；</li>
 *   <li>self-hosts 为完整 URL 形式时，URL 主机名匹配后被允许；</li>
 *   <li>站内相对路径（如 LocalStorageService 颁发的 /api/v1/local-storage/...）host 为 null 但以 / 开头 → 放行；</li>
 *   <li>外部域名 + 名单不含 → 抛 BAD_REQUEST("URL 域名不在允许列表内")；</li>
 *   <li>URL 为 null 或空字符串 → 不抛（保留"清空头像"语义）。</li>
 * </ol>
 */
class UserAvatarHostAllowlistTest {

    private UserService userService;
    private UserMapper userMapper;
    private SecurityProperties securityProperties;

    private MockedStatic<StpUtil> stpUtilMock;

    private static final long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        // 关键：使用真实 SecurityProperties 实例，便于按用例修改 upload 子属性
        securityProperties = new SecurityProperties();

        userService = new UserService(
                userMapper,
                mock(StudentNoMappingProperties.class),
                mock(ActiveTenantCache.class),
                mock(LoginLockoutService.class),
                mock(EmailVerificationCodeService.class),
                mock(TrustedProxyResolver.class),
                mock(HttpServletRequest.class),
                securityProperties,
                mock(AuditLogService.class),
                mock(SecurityMetrics.class),
                mock(WechatMiniProgramClient.class),
                mock(AiKnowledgeBaseMapper.class),
                mock(AiKbQaPairMapper.class));

        // updateProfile 主流程读 / 写不依赖 Sa-Token Session，但保险起见仍 mock 静态依赖
        stpUtilMock = mockStatic(StpUtil.class);
        stpUtilMock.when(StpUtil::getSession).thenReturn(mock(SaSession.class));
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
    }

    /** 构造一个已存在的 USER 角色用户作为 selectById 返回值。 */
    private User existingUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("alice@example.com");
        user.setRole("USER");
        user.setStatus(1);
        return user;
    }

    /** 构造一个仅修改 avatarUrl 的请求。 */
    private UpdateProfileRequest avatarReq(String avatarUrl) {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setAvatarUrl(avatarUrl);
        return req;
    }

    @Test
    @DisplayName("空白名单（allowed-asset-hosts 与 self-hosts 都为空）：拒绝任意外部 URL，语义反转为'仅配置生效'")
    void empty_allowlist_rejects_externalHost() {
        // 默认 SecurityProperties.Upload 两个列表都是空 ArrayList
        when(userMapper.selectById(USER_ID)).thenReturn(existingUser());

        UpdateProfileRequest req = avatarReq("https://evil.example.com/avatar.png");
        assertThatThrownBy(() -> userService.updateProfile(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置允许的资产域名");
    }

    @Test
    @DisplayName("self-hosts 隐式生效：完整 URL 形式的 self-host 解析出 host 后允许同主机的资产 URL")
    void selfHost_isImplicit() {
        // 模拟 dev 配置：self-hosts 来自 ${STORAGE_MINIO_ENDPOINT:}，是完整 URL 形式
        securityProperties.getUpload().setAllowedAssetHosts(List.of());
        securityProperties.getUpload().setSelfHosts(List.of("http://192.168.150.130:9000"));

        when(userMapper.selectById(USER_ID)).thenReturn(existingUser());
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UpdateProfileRequest req = avatarReq(
                "http://192.168.150.130:9000/campusforum/avatars/x.png?X-Amz-Sig=...");

        // 不应抛任何异常 — self-host 命中即放行
        assertThatCode(() -> userService.updateProfile(USER_ID, req))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("站内相对路径：host 为 null 但 url 以 / 开头时直接放行（兼容 LocalStorageService 颁发的 /api/v1/...）")
    void relativePath_alwaysAllowed() {
        // 即便白名单完全为空，相对路径也应被允许
        securityProperties.getUpload().setAllowedAssetHosts(List.of("only.example.com"));
        securityProperties.getUpload().setSelfHosts(List.of());

        when(userMapper.selectById(USER_ID)).thenReturn(existingUser());
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UpdateProfileRequest req = avatarReq("/api/v1/local-storage/2025-11-25/abc.png");

        assertThatCode(() -> userService.updateProfile(USER_ID, req))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("外部域名 + 名单不含：抛 BAD_REQUEST(\"URL 域名不在允许列表内\")")
    void externalHost_rejected() {
        securityProperties.getUpload().setAllowedAssetHosts(List.of("cdn.campus.example.com"));
        securityProperties.getUpload().setSelfHosts(List.of("http://192.168.150.130:9000"));

        when(userMapper.selectById(USER_ID)).thenReturn(existingUser());

        UpdateProfileRequest req = avatarReq("https://evil.example.com/avatar.png");
        assertThatThrownBy(() -> userService.updateProfile(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("URL 域名不在允许列表内");
    }

    @Test
    @DisplayName("URL 为 null 或空字符串：视为'清空头像'，不抛异常（保留现状）")
    void nullOrBlank_skipped() {
        // 空白名单也不会触发抛错 — 因为在白名单合并校验前就已经按 isBlank() 早返回
        when(userMapper.selectById(USER_ID)).thenReturn(existingUser());
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // null：UpdateProfileRequest.avatarUrl 为 null 时 controller 层根本不会调 assertHostAllowed
        // 因此本测试聚焦"明确传空字符串"的清空场景
        UpdateProfileRequest req = avatarReq("");
        assertThatCode(() -> userService.updateProfile(USER_ID, req))
                .doesNotThrowAnyException();

        // 仅有空白字符也应放行
        UpdateProfileRequest reqBlank = avatarReq("   ");
        assertThatCode(() -> userService.updateProfile(USER_ID, reqBlank))
                .doesNotThrowAnyException();
    }
}
