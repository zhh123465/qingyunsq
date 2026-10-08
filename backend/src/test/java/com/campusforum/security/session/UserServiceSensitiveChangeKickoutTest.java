package com.campusforum.security.session;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.common.BusinessException;
import com.campusforum.infra.audit.AuditContext;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.infra.email.EmailCodeScene;
import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.LoginLockoutService;
import com.campusforum.infra.security.SecurityProperties;
import com.campusforum.infra.security.TrustedProxyResolver;
import com.campusforum.tenant.TenantContext;
import com.campusforum.tenant.cache.ActiveTenantCache;
import com.campusforum.user.config.StudentNoMappingProperties;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import com.campusforum.user.service.EmailVerificationCodeService;
import com.campusforum.user.service.UserService;
import com.campusforum.wechat.service.WechatMiniProgramClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.MockedStatic;
import com.campusforum.ai.workspace.mapper.AiKnowledgeBaseMapper;
import com.campusforum.ai.workspace.mapper.AiKbQaPairMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：{@link UserService} 敏感凭证变更后强制踢下线 + 审计 + 监控埋点。
 *
 * <p>对应任务 T3.1 + T3.2，关联 bugfix.md 漏洞 5（修改密码 / 重置密码后旧
 * Sa-Token 仍可用，会话残留）。</p>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>不启动 Spring 容器，全部通过 Mockito 构造 {@link UserService}，
 *       避免把单元测试退化为集成测试 / 依赖虚拟机数据库；</li>
 *   <li>使用 {@code mockStatic} 同时拦截 {@link StpUtil} 与 {@link BCrypt}
 *       两个静态依赖：前者验证踢下线时序，后者绕过真实 BCrypt 哈希计算；</li>
 *   <li>{@link UserMapper} / {@link AuditLogService} / {@link SecurityMetrics}
 *       全部 mock，仅断言"是否被以正确参数调用"。</li>
 * </ul>
 *
 * <p>覆盖以下行为：</p>
 * <ol>
 *   <li>{@code changePassword} 成功后 → 调 {@link StpUtil#logoutByLoginId(Object)}
 *       + 写 {@code PASSWORD_CHANGE} 审计 + {@link SecurityMetrics#sessionForcedLogout(String)}；</li>
 *   <li>{@code resetPassword} 成功后 → 调用同上（action 为 {@code PASSWORD_RESET}）；</li>
 *   <li>{@link StpUtil#logoutByLoginId(Object)} 抛异常时主业务仍正常返回，
 *       且审计 + 监控埋点仍被调用（避免 Sa-Token / Redis 暂时不可用导致改密码失败）；</li>
 *   <li>旧密码错误时 → 抛 {@code WRONG_PASSWORD} 且**不应**触发踢下线 / 审计 / 监控。</li>
 * </ol>
 */
class UserServiceSensitiveChangeKickoutTest {

    /** 被测对象。 */
    private UserService userService;

    /** Mock 协作者。 */
    private UserMapper userMapper;
    private EmailVerificationCodeService emailVerificationCodeService;
    private AuditLogService auditLogService;
    private SecurityMetrics securityMetrics;
    private TrustedProxyResolver trustedProxyResolver;
    private HttpServletRequest httpRequest;

    /** 静态 mock 句柄，{@link AfterEach} 中关闭以避免污染其他测试。 */
    private MockedStatic<StpUtil> stpUtilMock;
    private MockedStatic<BCrypt> bcryptMock;

    /** 测试常量：默认租户 ID 与目标用户 ID。 */
    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 42L;
    private static final String DUMMY_HASH = "$2a$10$dummy.hash.value.for.unit.test.padding.padding.padding";
    private static final String NEW_HASH = "$2a$10$new.hash.value.for.unit.test.padding.padding.padding..";
    private static final String SALT = "$2a$10$dummy.salt.value.padding.padding..";

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        emailVerificationCodeService = mock(EmailVerificationCodeService.class);
        auditLogService = mock(AuditLogService.class);
        securityMetrics = mock(SecurityMetrics.class);
        trustedProxyResolver = mock(TrustedProxyResolver.class);
        httpRequest = mock(HttpServletRequest.class);

        // 其他 UserService 依赖在本场景不会被触达，给空 mock 即可
        userService = new UserService(
                userMapper,
                mock(StudentNoMappingProperties.class),
                mock(ActiveTenantCache.class),
                mock(LoginLockoutService.class),
                emailVerificationCodeService,
                trustedProxyResolver,
                httpRequest,
                mock(SecurityProperties.class),
                auditLogService,
                securityMetrics,
                mock(WechatMiniProgramClient.class),
                mock(AiKnowledgeBaseMapper.class),
                mock(AiKbQaPairMapper.class));

        // 静态 mock：StpUtil + BCrypt
        stpUtilMock = mockStatic(StpUtil.class);
        bcryptMock = mockStatic(BCrypt.class);

        // 默认 BCrypt 行为：gensalt 返回固定盐，hashpw 返回新哈希
        bcryptMock.when(() -> BCrypt.gensalt(10)).thenReturn(SALT);
        bcryptMock.when(() -> BCrypt.hashpw(anyString(), anyString())).thenReturn(NEW_HASH);

        // resetPassword 内部依赖 TenantContext.requireTenantId()
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
        bcryptMock.close();
        TenantContext.clear();
    }

    /**
     * 构造一个"已激活"的用户实体，模拟 DB 查询结果。
     */
    private User buildExistingUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("alice@example.com");
        user.setPasswordHash(DUMMY_HASH);
        user.setStatus(1);
        user.setRole("USER");
        return user;
    }

    @Test
    @DisplayName("changePassword 成功后：踢下线全部活跃 token + 写 PASSWORD_CHANGE 审计 + 监控埋点")
    void changePassword_invalidatesAllSessions_andAudits() {
        // 准备：DB 查到用户，旧密码校验通过
        User existing = buildExistingUser();
        when(userMapper.selectById(USER_ID)).thenReturn(existing);
        bcryptMock.when(() -> BCrypt.checkpw(eq("oldPwd"), eq(DUMMY_HASH))).thenReturn(true);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // 执行
        userService.changePassword(USER_ID, "oldPwd", "newPwd");

        // 断言：三件套全部触发
        // 注：sa-token-spring-boot3-starter 的"按 loginId 全量踢下线" API 是 StpUtil.logout(Object loginId)
        stpUtilMock.verify(() -> StpUtil.logout(USER_ID), times(1));
        verify(auditLogService).log(
                any(AuditContext.class),
                eq("PASSWORD_CHANGE"),
                eq("user"),
                eq(USER_ID),
                eq("all sessions invalidated"));
        verify(securityMetrics).sessionForcedLogout("PASSWORD_CHANGE");

        // 断言：密码哈希被更新成新 hash
        assertThat(existing.getPasswordHash()).isEqualTo(NEW_HASH);
    }

    @Test
    @DisplayName("resetPassword 成功后：踢下线全部活跃 token + 写 PASSWORD_RESET 审计 + 监控埋点")
    @SuppressWarnings("unchecked")
    void resetPassword_invalidatesAllSessions_andAudits() {
        // 准备：findTenantUser 走 selectOne(LambdaQueryWrapper) 路径
        User existing = buildExistingUser();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // 执行
        userService.resetPassword("alice@example.com", "123456", "newPwd");

        // 断言：邮箱验证码被消费 + 三件套全部触发
        verify(emailVerificationCodeService).verifyAndConsume(
                eq(TENANT_ID), eq("alice@example.com"), eq(EmailCodeScene.RESET_PASSWORD), eq("123456"));
        stpUtilMock.verify(() -> StpUtil.logout(USER_ID), times(1));
        verify(auditLogService).log(
                any(AuditContext.class),
                eq("PASSWORD_RESET"),
                eq("user"),
                eq(USER_ID),
                eq("all sessions invalidated"));
        verify(securityMetrics).sessionForcedLogout("PASSWORD_RESET");

        // 断言：密码哈希被更新；reset_token 字段被清空
        assertThat(existing.getPasswordHash()).isEqualTo(NEW_HASH);
        assertThat(existing.getResetToken()).isNull();
        assertThat(existing.getResetTokenExpires()).isNull();
    }

    @Test
    @DisplayName("logoutByLoginId 抛异常时：主业务仍正常返回，审计 + 监控仍被调用")
    void changePassword_kickoutFails_doesNotPropagate() {
        // 准备：DB 查到用户，旧密码校验通过；StpUtil.logout 抛 RuntimeException
        User existing = buildExistingUser();
        when(userMapper.selectById(USER_ID)).thenReturn(existing);
        bcryptMock.when(() -> BCrypt.checkpw(eq("oldPwd"), eq(DUMMY_HASH))).thenReturn(true);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        stpUtilMock.when(() -> StpUtil.logout(USER_ID))
                .thenThrow(new RuntimeException("Sa-Token Redis 不可用"));

        // 执行：不应抛异常
        userService.changePassword(USER_ID, "oldPwd", "newPwd");

        // 断言：即使踢下线失败，审计 + 监控埋点仍被调用（避免静默失败丢可观测性）
        verify(auditLogService).log(
                any(AuditContext.class),
                eq("PASSWORD_CHANGE"),
                eq("user"),
                eq(USER_ID),
                eq("all sessions invalidated"));
        verify(securityMetrics).sessionForcedLogout("PASSWORD_CHANGE");
    }

    @Test
    @DisplayName("changePassword 旧密码错误：抛 WRONG_PASSWORD 且不触发踢下线 / 审计 / 监控")
    void changePassword_oldPasswordWrong_doesNotKickout() {
        // 准备：DB 查到用户，但旧密码校验失败
        User existing = buildExistingUser();
        when(userMapper.selectById(USER_ID)).thenReturn(existing);
        bcryptMock.when(() -> BCrypt.checkpw(eq("wrongPwd"), eq(DUMMY_HASH))).thenReturn(false);

        // 执行：抛 BusinessException(WRONG_PASSWORD)
        assertThatThrownBy(() -> userService.changePassword(USER_ID, "wrongPwd", "newPwd"))
                .isInstanceOf(BusinessException.class);

        // 断言：踢下线 / 审计 / 监控埋点都不应被触发
        stpUtilMock.verify(() -> StpUtil.logout(any()), never());
        verify(auditLogService, never()).log(
                any(AuditContext.class), anyString(), anyString(), any(), anyString());
        verify(securityMetrics, never()).sessionForcedLogout(anyString());

        // 旧密码失败时也不应调用 updateById（密码哈希未变更）
        verify(userMapper, never()).updateById(any(User.class));
        assertThat(existing.getPasswordHash()).isEqualTo(DUMMY_HASH);
    }
}
