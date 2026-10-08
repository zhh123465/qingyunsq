package com.campusforum.infra.security;

import com.campusforum.infra.ratelimit.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 启动期安全相关配置严格校验器（bugfix.md 主题 1：凭证管理与启动校验）。
 *
 * <p>整体策略：
 * <ul>
 *   <li><b>prod profile 严格阻断</b>：弱默认值 / 长度不足 / 敏感路径被加入限流排除等
 *       一律抛 {@link IllegalStateException} 让应用启动失败，强制运维不得使用任何弱默认值；</li>
 *   <li><b>dev profile 零摩擦</b>：同样的弱默认值仅以 WARN 形式提示，确保本地起服务无障碍；</li>
 *   <li><b>非 profile 维度的配置错误</b>（例如 rate-limit 把 {@code /api/v1/auth/login} 加进 exclude）
 *       <b>所有 profile 都阻断</b>，避免 dev 配错被推到 prod。</li>
 * </ul>
 *
 * <p>校验项（一一对应 bugfix.md 漏洞编号，参见每个 {@code validateXxx} 方法的 javadoc）：
 * <ol>
 *   <li>{@link #validateCrypto(boolean)} —— 漏洞 1：v1 ECB 旧密钥与 master-key 强度</li>
 *   <li>{@link #validateSignedUrlSecret(boolean)} —— 漏洞 3：signed-url-secret 弱默认仅 WARN</li>
 *   <li>{@link #validateRedisPassword(boolean)} —— 漏洞 4：Redis 凭证强度</li>
 *   <li>{@link #validateRateLimitExcludePatterns()} —— 漏洞 10：敏感路径不可被加入 exclude-patterns</li>
 *   <li>{@link #validateLegacyCutoverDates(boolean)} —— 漏洞 1（crypto cutover）+ 漏洞 8（ws-ticket cutover）</li>
 * </ol>
 *
 * <p>该校验器优先级 {@code @Order(20)}，低于 {@code TenantStartupValidator}，
 * 即先确保租户基础设施 ready，再做安全配置校验，方便排查时按顺序定位问题。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class SecurityStartupValidator implements ApplicationRunner {

    /** AES-GCM 主密钥最小长度（字节）。 */
    private static final int MIN_MASTER_KEY_BYTES = 32;

    /** Redis 密码在 prod 环境的最小长度（字节）。 */
    private static final int MIN_REDIS_PASSWORD_BYTES = 16;

    /**
     * 弱默认值禁用 token 列表。
     *
     * <p>任意密钥值在 prod profile 下若 {@code String#contains} 命中下列任一 token 即视为
     * "仍在使用仓库默认占位串"，直接抛错阻止启动。dev profile 仅 WARN，方便本地复用默认值。</p>
     *
     * <ul>
     *   <li>{@code please-override}：来自 {@code application.yml} 的 placeholder 提示文本</li>
     *   <li>{@code dev-only-change-me}：开发态默认值前缀</li>
     *   <li>{@code ChangeMe}：MeiliSearch / MinIO 早期模板的默认密码</li>
     *   <li>{@code minioadmin}：MinIO 出厂默认 access-key / secret-key</li>
     * </ul>
     */
    private static final List<String> FORBIDDEN_DEFAULT_TOKENS = List.of(
            "please-override", "dev-only-change-me", "ChangeMe", "minioadmin");

    /**
     * 限流不可排除的敏感路径前缀。
     *
     * <p>对应 bugfix.md 漏洞 10：早期 {@code rate-limit.exclude-patterns} 默认值含
     * {@code /api/v1/auth/login}，让暴力破解、撞库、AI 滥用等敏感入口被直接放行。
     * 任何与下列前缀重叠的 pattern 都会在启动期被拒绝（不分 profile）。</p>
     */
    private static final Set<String> SENSITIVE_PATH_PREFIXES = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/wechat-login",
            "/api/v1/auth/register",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/ws-ticket",
            "/api/v1/ai/");

    private final SecurityProperties props;
    private final Environment env;
    private final RateLimitProperties rateLimitProperties;

    /**
     * Spring 上下文 ready 后由 {@link ApplicationRunner} 触发的入口。
     *
     * <p>按"密钥强度 → 凭证强度 → 配置一致性 → 兼容期截止日期"顺序执行 5 项校验，
     * 任何一项失败均通过 {@link IllegalStateException} 中断启动。</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
        validateCrypto(isProd);
        validateSignedUrlSecret(isProd);
        validateRedisPassword(isProd);
        validateRateLimitExcludePatterns();
        validateLegacyCutoverDates(isProd);
    }

    /**
     * 校验 AES-GCM 主密钥（bugfix.md 漏洞 1）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code legacy-mode=true} 仅 WARN 后 return（紧急回滚场景，跳过新加密路径）；</li>
     *   <li>master-key 缺失或为空白字符串 → 抛错；</li>
     *   <li>UTF-8 字节长度 &lt; {@link #MIN_MASTER_KEY_BYTES} → 抛错；</li>
     *   <li>prod 命中 {@link #FORBIDDEN_DEFAULT_TOKENS} → 抛错；</li>
     *   <li>dev 命中 forbidden token 仅 WARN，便于本地复用默认值。</li>
     * </ul>
     */
    private void validateCrypto(boolean isProd) {
        SecurityProperties.Crypto cfg = props.getCrypto();
        if (cfg.isLegacyMode()) {
            log.warn("SecurityStartupValidator: crypto legacy-mode is ENABLED — 仅紧急回滚使用，请尽快恢复");
            return;
        }
        String key = cfg.getMasterKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "security.crypto.master-key 未配置。请通过环境变量 CRYPTO_MASTER_KEY 注入 ≥ 32 字节随机字符串");
        }
        int byteLen = key.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen < MIN_MASTER_KEY_BYTES) {
            throw new IllegalStateException(
                    "security.crypto.master-key 长度 " + byteLen + " 字节不足，需 ≥ " + MIN_MASTER_KEY_BYTES);
        }
        if (containsForbiddenToken(key)) {
            if (isProd) {
                throw new IllegalStateException(
                        "生产环境 security.crypto.master-key 仍包含弱默认值 token，请通过 CRYPTO_MASTER_KEY 覆盖");
            }
            log.warn("SecurityStartupValidator: crypto master-key 仍为开发占位值，生产部署必须通过 CRYPTO_MASTER_KEY 覆盖");
            return;
        }
        log.info("SecurityStartupValidator: crypto master-key length={} bytes OK", byteLen);
    }

    /**
     * 校验签名 URL HMAC 密钥（bugfix.md 漏洞 3）。
     *
     * <p>规则：
     * <ul>
     *   <li>缺失或空白 → 抛错；</li>
     *   <li>prod 命中 {@link #FORBIDDEN_DEFAULT_TOKENS} → 抛错；</li>
     *   <li>UTF-8 字节长度 &lt; 32：prod 抛错、dev 仅 WARN；</li>
     *   <li>dev 命中 forbidden token 仅 WARN。</li>
     * </ul>
     */
    private void validateSignedUrlSecret(boolean isProd) {
        String s = props.getSignedUrlSecret();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException(
                    "security.signed-url-secret 未配置。请通过环境变量 SIGNED_URL_SECRET 注入 ≥ 32 字节随机字符串");
        }
        if (containsForbiddenToken(s)) {
            if (isProd) {
                throw new IllegalStateException(
                        "生产环境 security.signed-url-secret 仍为默认值，请通过 SIGNED_URL_SECRET 覆盖");
            }
            log.warn("SecurityStartupValidator: signed-url-secret 仍为开发占位值，生产部署必须通过 SIGNED_URL_SECRET 覆盖");
        }
        int byteLen = s.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen < 32) {
            if (isProd) {
                throw new IllegalStateException(
                        "security.signed-url-secret 长度 " + byteLen + " 字节不足，需 ≥ 32");
            }
            log.warn("SecurityStartupValidator: signed-url-secret 长度 {} 字节不足 32，生产部署会启动失败", byteLen);
        }
    }

    /**
     * 校验 Redis 密码强度（bugfix.md 漏洞 4）。
     *
     * <p>仅在 prod profile 下生效；dev / test 直接 return，避免 {@code my-redis} 历史容器
     * 的 {@code 123456} 默认密码影响本地开发。</p>
     *
     * <p>Sa-Token tik 风格 token 与签名 URL 都最终落到 Redis，Redis 凭证泄漏即等同于全站会话窃取，
     * 因此这里要求 prod 长度 ≥ {@link #MIN_REDIS_PASSWORD_BYTES} 且不命中弱默认 token。</p>
     */
    private void validateRedisPassword(boolean isProd) {
        if (!isProd) {
            return;
        }
        String pwd = env.getProperty("spring.data.redis.password", "");
        int byteLen = pwd.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen < MIN_REDIS_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "生产环境 Redis 密码长度 " + byteLen + " 字节不足，需 ≥ " + MIN_REDIS_PASSWORD_BYTES);
        }
        if (containsForbiddenToken(pwd)) {
            throw new IllegalStateException(
                    "生产环境 Redis 密码仍为默认值（含弱 token），请通过 REDIS_PASSWORD 注入随机强密码");
        }
    }

    /**
     * 校验限流排除路径不得覆盖敏感入口（bugfix.md 漏洞 10）。
     *
     * <p>该校验在所有 profile 都生效，避免 dev 配错被发到 prod。算法：
     * 对每条 {@code rate-limit.exclude-patterns} pattern，逐一与 {@link #SENSITIVE_PATH_PREFIXES} 比较：
     * <ul>
     *   <li>若 {@code pattern.startsWith(sensitive)} 则 pattern 已经包含敏感前缀本身（如
     *       {@code /api/v1/auth/login} 或 {@code /api/v1/auth/login/**}）；</li>
     *   <li>若 {@code sensitive.startsWith(pattern去掉/**)} 则 pattern 是敏感前缀的祖先（如
     *       {@code /api/v1/**} 包含 {@code /api/v1/auth/login}）。</li>
     * </ul>
     * 任意一种情况都视为"敏感路径被加入限流排除"，抛错阻止启动。</p>
     */
    private void validateRateLimitExcludePatterns() {
        List<String> patterns = rateLimitProperties.getExcludePatterns();
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            // 去掉末尾通配符再做祖先匹配，避免 "/api/v1/**" 这种 pattern 被错过
            String prefix = pattern.endsWith("/**")
                    ? pattern.substring(0, pattern.length() - 3)
                    : pattern;
            for (String sensitive : SENSITIVE_PATH_PREFIXES) {
                boolean patternIsUnderSensitive = pattern.startsWith(sensitive);
                boolean sensitiveIsUnderPattern = !prefix.isEmpty() && sensitive.startsWith(prefix);
                if (patternIsUnderSensitive || sensitiveIsUnderPattern) {
                    throw new IllegalStateException(
                            "敏感路径不可被加入 rate-limit.exclude-patterns: " + pattern);
                }
            }
        }
    }

    /**
     * 校验兼容期截止日期（bugfix.md 漏洞 1 crypto cutover + 漏洞 8 ws-ticket cutover）。
     *
     * <p>仅在 prod profile 下生效。规则：
     * <ul>
     *   <li>{@code crypto.legacy-cutover-date} 已过期且 {@code legacy-mode=false}：仅 ERROR 日志，
     *       不抛错。原因：v1 数据可能尚未异步重加密完，强行抛错会让生产无法启动；
     *       仍以日志告警 + 监控指标驱动运维清理。</li>
     *   <li>{@code crypto.legacy-cutover-date} 已过期且 {@code legacy-mode=true}：抛错。
     *       legacy-mode 是紧急回滚开关，过了 cutover 仍开着说明运维忘记关闭。</li>
     *   <li>{@code ws-ticket.enforced-cutover-date} 已过期且 {@code enforced=false}：抛错，
     *       强制运维设置 {@code WS_TICKET_ENFORCED=true}。</li>
     * </ul>
     */
    private void validateLegacyCutoverDates(boolean isProd) {
        if (!isProd) {
            return;
        }
        LocalDate today = LocalDate.now();

        SecurityProperties.Crypto crypto = props.getCrypto();
        LocalDate cryptoCutover = crypto.getLegacyCutoverDate();
        if (cryptoCutover != null && today.isAfter(cryptoCutover)) {
            if (crypto.isLegacyMode()) {
                throw new IllegalStateException(
                        "crypto legacy-mode cutover 已到期 (" + cryptoCutover
                                + ")，请关闭 CRYPTO_LEGACY_MODE 并完成 v1 数据迁移");
            }
            log.error("SecurityStartupValidator: crypto legacy cutover 已到期 ({})，"
                    + "若仍有 v1 ECB 密文需异步重加密，请评估是否完成迁移", cryptoCutover);
        }

        SecurityProperties.WsTicket wsTicket = props.getWsTicket();
        LocalDate wsCutover = wsTicket.getEnforcedCutoverDate();
        if (wsCutover != null && today.isAfter(wsCutover) && !wsTicket.isEnforced()) {
            throw new IllegalStateException(
                    "WS ticket cutover 已到期 (" + wsCutover + ")，请设置 WS_TICKET_ENFORCED=true");
        }
    }

    /**
     * 判断给定值是否命中弱默认值禁用 token（null 安全）。
     *
     * @param value 待检查的密钥 / 凭证字符串，允许为 null
     * @return 命中任一 forbidden token 返回 true；null / 空串 / 未命中均返回 false
     */
    private boolean containsForbiddenToken(String value) {
        if (value == null) {
            return false;
        }
        return FORBIDDEN_DEFAULT_TOKENS.stream().anyMatch(value::contains);
    }
}
