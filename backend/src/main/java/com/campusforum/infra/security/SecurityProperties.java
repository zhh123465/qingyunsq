package com.campusforum.infra.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台安全相关的可配置项。
 *
 * <p>这些参数集中在 {@code security} 命名空间下，便于运维通过环境变量在不重启代码的情况下调整。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    /** 登录失败锁定相关配置。 */
    private LoginLockout loginLockout = new LoginLockout();

    /** 可信反向代理 IP 列表（CIDR 或单 IP）。命中时才解析 X-Forwarded-For/X-Real-IP。 */
    private List<String> trustedProxies = new ArrayList<>(List.of(
            "127.0.0.1", "::1",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
    ));

    /**
     * 资源签名 URL HMAC 密钥。
     *
     * <p>无字面默认值；dev profile 由 {@code application-dev.yml} 提供 dev-only 默认值，
     * prod 必须通过 {@code SIGNED_URL_SECRET} 环境变量覆盖。
     * {@code SecurityStartupValidator} 在启动期严格校验长度与弱默认 token（漏洞 3）。</p>
     */
    private String signedUrlSecret;

    /** 资源签名 URL 默认有效期（秒），下载/预览短期一次性使用足以。 */
    private int signedUrlTtlSeconds = 60;

    /** AI baseUrl 是否禁止指向私网/本机地址，避免 SSRF。生产保持 true。 */
    private boolean aiBaseUrlBlockPrivateNetwork = true;

    /** 对称加密相关配置（AES-GCM 主密钥及兼容旗标）。 */
    private Crypto crypto = new Crypto();

    /** CORS 跨域配置，生产必须由运维通过环境变量显式覆盖。 */
    private Cors cors = new Cors();

    /** 文件上传相关安全配置。 */
    private Upload upload = new Upload();

    /** WebSocket 一次性票据配置。 */
    private WsTicket wsTicket = new WsTicket();

    /**
     * API 文档（Swagger / Knife4j / api-docs）暴露策略。
     *
     * <p>对应 bugfix.md 漏洞 2：生产环境下接口契约必须默认关闭，
     * 仅在 {@link Docs#getEnabledProfiles()} 列出的 profile 中允许暴露，
     * 且仍需配合 {@code DocAccessFilter} 校验来源 IP（trusted-proxies）。</p>
     *
     * <p>默认值仅包含 {@code dev} 与 {@code test}，{@code prod} 不在其中，
     * 因此不在白名单内的 profile 由 DocAccessFilter 直接返回 404 静默拦截。</p>
     */
    private Docs docs = new Docs();

    @Data
    public static class LoginLockout {
        /** 是否启用失败锁定。 */
        private boolean enabled = true;

        /** 触发账号锁定的连续失败次数。 */
        private int maxFailures = 10;

        /** 失败计数滑动窗口（秒）。 */
        private int windowSeconds = 900;

        /** 账号锁定持续时间（秒），缩短为 5 分钟以减少针对性 DoS 影响。 */
        private int lockoutSeconds = 300;

        /** IP 维度触发锁定的连续失败次数（与账号维度独立）。 */
        private int ipMaxFailures = 20;

        /** IP 维度锁定持续时间（秒）。 */
        private int ipLockoutSeconds = 900;
    }

    @Data
    public static class Crypto {
        /** AES-GCM 主密钥（生产从 ENV CRYPTO_MASTER_KEY 注入），长度需 ≥ 32 字节。 */
        private String masterKey;

        /** 紧急回滚开关：true 时跳过新加密路径，沿用旧 ECB 实现。 */
        private boolean legacyMode = false;

        /**
         * v1 ECB 兼容期截止日期。
         *
         * <p>对应 bugfix.md 漏洞 1：旧版 ECB 加密以 {@code CryptoUtils} 硬编码密钥写入，
         * 已被收缩为只读迁移路径。该截止日期到来后，{@code SecurityStartupValidator}
         * 会在 prod profile 下拒绝继续启动（除非显式开启 {@link #legacyMode}）。</p>
         *
         * <p>典型配置场景：在 {@code application.yml} 中写入
         * {@code security.crypto.legacy-cutover-date: 2026-09-01}，
         * 给历史 v1 ECB 数据预留 6 个月异步重加密窗口。</p>
         *
         * <p>默认值 {@code null} 表示未设置 cutover；建议显式配置。</p>
         */
        private LocalDate legacyCutoverDate;
    }

    @Data
    public static class Cors {
        /** 允许的跨域来源列表，生产环境必须显式配置前端域名。 */
        private List<String> allowedOrigins = new ArrayList<>();

        /** 允许的请求方法。 */
        private List<String> allowedMethods = new ArrayList<>(List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    }

    @Data
    public static class Upload {
        /** 是否使用 Apache Tika 检测真实 MIME 类型。 */
        private boolean realMimeCheck = true;

        /**
         * 是否允许上传任意扩展名文件（2026-07-13 审核流引入）。
         *
         * <p>开启后：扩展名白名单（upload.allowed-extensions）不再拦截，
         * {@code MimeTypeValidator} 对未注册扩展名放行（已注册的仍做 Tika 交叉验证，
         * MIME 黑名单始终生效）。安全前提是资源审核流：普通用户上传落
         * status=2 待审核，人工审核通过后才对外发布，机器甄别责任转移给审核人。</p>
         *
         * <p>默认 false 保持旧白名单行为；生产由 {@code UPLOAD_ALLOW_ANY_EXTENSION} 开启。</p>
         */
        private boolean allowAnyExtension = false;

        /** 全局禁止上传的扩展名（在 allowed-extensions 之上的二次过滤）。 */
        private List<String> blockedExtensions = new ArrayList<>();

        /**
         * 全局 MIME 黑名单：Tika 实测出的真实 MIME 命中即拒绝上传。
         *
         * <p>对应 bugfix.md 漏洞 24：{@code MimeTypeValidator} 早期对未在白名单内的
         * 扩展名静默放行，导致改名为 {@code .png} 的 PHP 木马被存储后台。
         * 引入黑名单后，**优先于扩展名白名单**做拒绝判定。</p>
         *
         * <p>典型配置场景：列出可执行 / 脚本 / 宏类型，例如
         * {@code application/x-php}、{@code application/x-msdownload}、
         * {@code application/x-msdos-program}、{@code application/x-bat}、
         * {@code application/x-sh}、{@code text/x-php}、{@code text/x-script.python}。</p>
         *
         * <p>默认值为空列表，表示未启用黑名单（等待 T1.4 在 yml 中补默认值）。</p>
         */
        private List<String> blockedMimeTypes = new ArrayList<>();

        /** 头像 / 封面 URL 允许的域名白名单（用于反 XSS 与 Open Redirect）。 */
        private List<String> allowedAssetHosts = new ArrayList<>();

        /**
         * 本站对象存储域名列表。
         *
         * <p>对应 bugfix.md 漏洞 15：{@code UserService#assertHostAllowed} 在
         * {@code allowedAssetHosts} 为空时静默放行任意外部 URL，构成 Open Redirect 与 SSRF 风险。
         * 引入 self-hosts 后，与 {@link #allowedAssetHosts} **合并**作为头像 / 封面 URL 白名单，
         * 且空白名单语义反转为"仅允许本站存储域名"而非"全放行"。</p>
         *
         * <p>典型配置场景：从 storage 端点推导，例如
         * {@code security.upload.self-hosts: [${STORAGE_MINIO_ENDPOINT:}]}，
         * 这样 MinIO 自身颁发的 presigned URL 永远在白名单内，无需运维额外维护。</p>
         *
         * <p>默认值为空列表（等待 T1.4 在 yml 中补默认值）。</p>
         */
        private List<String> selfHosts = new ArrayList<>();
    }

    @Data
    public static class WsTicket {
        /** WebSocket 票据有效期（秒），短即可，仅用于建立 WS 连接。 */
        private int ttlSeconds = 30;

        /** true 时拒绝旧 token 走 query string，强制使用 ticket。 */
        private boolean enforced = false;

        /**
         * WS legacy token 强制 cutover 日期。
         *
         * <p>对应 bugfix.md 漏洞 8：在 {@link #enforced} 默认 {@code false} 的兼容期内，
         * 旧客户端仍可通过 {@code ?token=<sa-token>} 建立 WebSocket 连接，
         * 主令牌会被 nginx access log / 浏览器历史 / Referer / APM 监控持续记录。
         * 该日期到来后，{@code SecurityStartupValidator} 在 prod profile 下要求
         * {@link #enforced} 必须为 {@code true}，否则拒绝启动。</p>
         *
         * <p>典型配置场景：在 {@code application.yml} 中写入
         * {@code security.ws-ticket.enforced-cutover-date: 2026-07-01}，
         * 留 1 个月给前端做客户端升级灰度。</p>
         *
         * <p>默认值 {@code null} 表示未设置 cutover；建议显式配置。</p>
         */
        private LocalDate enforcedCutoverDate;
    }

    /**
     * API 文档暴露策略子配置。
     *
     * <p>声明哪些 profile 允许暴露 swagger / api-docs；
     * 不在列表内的 profile 由 {@code DocAccessFilter} 直接 404 静默拦截。
     * 与 {@code springdoc.api-docs.enabled} / {@code springdoc.swagger-ui.enabled}
     * 形成双重防御（运维误覆盖配置项时仍由 Filter 兜底）。</p>
     */
    @Data
    public static class Docs {

        /**
         * 允许暴露 API 文档的 profile 列表。
         *
         * <p>典型配置场景：
         * {@code security.docs.enabled-profiles: [dev, test]}（默认）；
         * 严格生产部署可保持默认值即可——{@code prod} 不在白名单内，
         * 即便运维误开 {@code SPRINGDOC_ENABLED=true} 也会被 DocAccessFilter 拦截。</p>
         *
         * <p>默认值包含 {@code dev} 与 {@code test}，便于本地开发与集成测试访问 swagger UI。</p>
         */
        private List<String> enabledProfiles = new ArrayList<>(List.of("dev", "test"));
    }
}
