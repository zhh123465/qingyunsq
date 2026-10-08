package com.campusforum.security.startup;

import com.campusforum.infra.ratelimit.RateLimitProperties;
import com.campusforum.infra.security.SecurityProperties;
import com.campusforum.infra.security.SecurityStartupValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecurityStartupValidator prod profile 严格阻断分支单元测试（T1.3）。
 *
 * <p>覆盖 bugfix.md 漏洞 1 / 3 / 4 / 8 / 10 在 prod profile 下应该抛 IllegalStateException
 * 终止启动的所有路径。使用纯单元测试 + MockEnvironment + 真实 POJO，不启动 Spring 上下文，
 * 也不依赖 MySQL / Redis，符合 T1.3 "纯单元测试 + 编译" 的环境约束。</p>
 */
class SecurityStartupValidatorProdTest {

    private MockEnvironment env;
    private SecurityProperties props;
    private RateLimitProperties rateLimitProps;
    private final ApplicationArguments args = new ApplicationArguments() {
        @Override public String[] getSourceArgs() { return new String[0]; }
        @Override public java.util.Set<String> getOptionNames() { return java.util.Set.of(); }
        @Override public boolean containsOption(String name) { return false; }
        @Override public List<String> getOptionValues(String name) { return List.of(); }
        @Override public List<String> getNonOptionArgs() { return List.of(); }
    };

    /**
     * 构造一个"prod profile + 全部健康默认值"的初始环境，
     * 让每个测试只需要污染自己关心的那一项即可。
     */
    @BeforeEach
    void setUp() {
        env = new MockEnvironment();
        env.addActiveProfile("prod");
        // prod 环境下健康的 Redis 密码（≥ 16 字节，且不含 forbidden token）
        env.setProperty("spring.data.redis.password", "Strong-RedIs-Pwd-2026");

        props = new SecurityProperties();
        // 健康的 master-key：64 字节随机风格，无 forbidden token
        props.getCrypto().setMasterKey("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678ABCDEF0123456789abcdef99");
        props.getCrypto().setLegacyMode(false);
        // 健康的 signed-url-secret：≥ 32 字节，无 forbidden token
        props.setSignedUrlSecret("ProdSignedUrlSecretStrong-2026-RandomBytes!!");
        // 兼容期 cutover 默认设为远未来，避免误命中
        props.getCrypto().setLegacyCutoverDate(LocalDate.now().plusYears(5));
        props.getWsTicket().setEnforced(true);
        props.getWsTicket().setEnforcedCutoverDate(LocalDate.now().plusYears(5));

        rateLimitProps = new RateLimitProperties();
        rateLimitProps.setExcludePatterns(new ArrayList<>(List.of("/actuator/**")));
    }

    private SecurityStartupValidator newValidator() {
        return new SecurityStartupValidator(props, env, rateLimitProps);
    }

    // ---------------- master-key (漏洞 1) ----------------

    @Test
    @DisplayName("prod_weakMasterKey_throws：master-key 含 dev-only-change-me 且长度足 → 抛错")
    void prod_weakMasterKey_throws() {
        // 长度 36 字节，足够 ≥ 32，但仍含 dev-only-change-me 弱默认 token
        props.getCrypto().setMasterKey("dev-only-change-me-32-bytes-padding!");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crypto.master-key")
                .hasMessageContaining("弱默认值");
    }

    @Test
    @DisplayName("prod_masterKey_tooShort_throws：长度 < 32 字节 → 抛错")
    void prod_masterKey_tooShort_throws() {
        props.getCrypto().setMasterKey("short-key");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("prod_masterKey_blank_throws：空值 → 抛错")
    void prod_masterKey_blank_throws() {
        props.getCrypto().setMasterKey("");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key 未配置");
    }

    @Test
    @DisplayName("prod_legacyMode_skips_cryptoValidation：legacyMode=true 时仅 WARN 不抛")
    void prod_legacyMode_skips_cryptoValidation() {
        props.getCrypto().setLegacyMode(true);
        // 即使 master-key 为空也不应抛错（legacy 分支提前 return）
        props.getCrypto().setMasterKey("");

        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
    }

    // ---------------- signed-url-secret (漏洞 3) ----------------

    @Test
    @DisplayName("prod_signedUrlSecret_tooShort_throws：长度 < 32 字节 → 抛错")
    void prod_signedUrlSecret_tooShort_throws() {
        props.setSignedUrlSecret("short-secret-only-20byte!!");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed-url-secret")
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("prod_signedUrlSecret_pleaseOverride_throws：包含 please-override 弱 token → 抛错")
    void prod_signedUrlSecret_pleaseOverride_throws() {
        // 长度 ≥ 32，但仍命中 forbidden token
        props.setSignedUrlSecret("any-prefix-please-override-padding-32bytes!!");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed-url-secret");
    }

    @Test
    @DisplayName("prod_signedUrlSecret_blank_throws：空值 → 抛错")
    void prod_signedUrlSecret_blank_throws() {
        props.setSignedUrlSecret(null);

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed-url-secret 未配置");
    }

    // ---------------- redis password (漏洞 4) ----------------

    @Test
    @DisplayName("prod_redisPasswordTooShort_throws：长度 < 16 → 抛错")
    void prod_redisPasswordTooShort_throws() {
        env.setProperty("spring.data.redis.password", "123456");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis")
                .hasMessageContaining("16");
    }

    @Test
    @DisplayName("prod_redisPassword_default_throws：包含 ChangeMe 弱 token → 抛错")
    void prod_redisPassword_default_throws() {
        // 长度 24 ≥ 16，但仍命中 ChangeMe forbidden token
        env.setProperty("spring.data.redis.password", "redis-ChangeMe-2026-pwd!");

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis")
                .hasMessageContaining("默认值");
    }

    // ---------------- rate-limit exclude patterns (漏洞 10) ----------------

    @Test
    @DisplayName("exclude_login_throws：rate-limit.exclude-patterns 含 /api/v1/auth/login → 抛错")
    void exclude_login_throws() {
        rateLimitProps.setExcludePatterns(List.of("/actuator/**", "/api/v1/auth/login"));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感路径不可被加入 rate-limit.exclude-patterns")
                .hasMessageContaining("/api/v1/auth/login");
    }

    @Test
    @DisplayName("exclude_login_glob_throws：含 /api/v1/auth/login/** → 抛错")
    void exclude_login_glob_throws() {
        rateLimitProps.setExcludePatterns(List.of("/api/v1/auth/login/**"));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感路径");
    }

    @Test
    @DisplayName("exclude_wechatLogin_throws：rate-limit.exclude-patterns 含微信登录 → 抛错")
    void exclude_wechatLogin_throws() {
        rateLimitProps.setExcludePatterns(List.of("/api/v1/auth/wechat-login"));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感路径不可被加入 rate-limit.exclude-patterns")
                .hasMessageContaining("/api/v1/auth/wechat-login");
    }

    @Test
    @DisplayName("exclude_authGlob_throws：含 /api/v1/auth/** 这种祖先 pattern 也应抛")
    void exclude_authGlob_throws() {
        rateLimitProps.setExcludePatterns(List.of("/api/v1/auth/**"));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感路径");
    }

    @Test
    @DisplayName("exclude_aiGlob_throws：含 /api/v1/ai/** 应抛（避免 AI 滥用）")
    void exclude_aiGlob_throws() {
        rateLimitProps.setExcludePatterns(List.of("/api/v1/ai/**"));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感路径");
    }

    @Test
    @DisplayName("exclude_actuator_only_passes：仅 /actuator/** 不命中敏感前缀 → 通过")
    void exclude_actuator_only_passes() {
        rateLimitProps.setExcludePatterns(List.of("/actuator/**"));

        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
    }

    // ---------------- ws ticket cutover (漏洞 8) ----------------

    @Test
    @DisplayName("prod_ws_cutover_expired_and_notEnforced_throws：cutover 已过 + enforced=false → 抛")
    void prod_ws_cutover_expired_and_notEnforced_throws() {
        props.getWsTicket().setEnforced(false);
        props.getWsTicket().setEnforcedCutoverDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WS ticket cutover")
                .hasMessageContaining("WS_TICKET_ENFORCED");
    }

    @Test
    @DisplayName("prod_ws_cutover_notExpired_does_not_throw：cutover 是未来日期 → 通过")
    void prod_ws_cutover_notExpired_does_not_throw() {
        props.getWsTicket().setEnforced(false);
        props.getWsTicket().setEnforcedCutoverDate(LocalDate.now().plusDays(30));

        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod_ws_cutover_expired_but_enforced_does_not_throw：cutover 过期但 enforced=true → 通过")
    void prod_ws_cutover_expired_but_enforced_does_not_throw() {
        props.getWsTicket().setEnforced(true);
        props.getWsTicket().setEnforcedCutoverDate(LocalDate.now().minusDays(10));

        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
    }

    // ---------------- crypto cutover (漏洞 1) ----------------

    @Test
    @DisplayName("prod_crypto_cutover_expired_legacyOff_doesNotThrow：仅 ERROR 日志不抛")
    void prod_crypto_cutover_expired_legacyOff_doesNotThrow() {
        props.getCrypto().setLegacyMode(false);
        props.getCrypto().setLegacyCutoverDate(LocalDate.now().minusDays(1));

        // 即便 cutover 已到期，只要 legacyMode=false 且其他校验通过就不应抛
        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod_crypto_cutover_expired_legacyOn_throws：cutover 已过 + legacyMode=true → 抛")
    void prod_crypto_cutover_expired_legacyOn_throws() {
        props.getCrypto().setLegacyMode(true);
        props.getCrypto().setLegacyCutoverDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> newValidator().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crypto legacy-mode cutover");
    }

    // ---------------- happy path ----------------

    @Test
    @DisplayName("prod_allHealthy_passes：所有校验全部通过的健康基线")
    void prod_allHealthy_passes() {
        assertThatCode(() -> newValidator().run(args)).doesNotThrowAnyException();
        assertThat(env.getActiveProfiles()).contains("prod");
    }
}
