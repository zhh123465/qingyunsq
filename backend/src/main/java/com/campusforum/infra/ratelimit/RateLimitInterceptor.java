package com.campusforum.infra.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.TrustedProxyResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局限流拦截器。
 *
 * <p>本拦截器对应 bugfix.md 漏洞 7（限流 key 含 path variable）的修复落地：
 * 早期实现把 {@code request.getRequestURI()} 直接拼进限流 key，让含
 * {@code {id}} 等路径变量的端点（例如 {@code /api/v1/posts/{id}}）按"具体 ID"
 * 单独分桶，攻击者通过轮询不同 ID 即可线性绕过单端点配额。改造后：
 * <ul>
 *   <li>限流 key 与 endpoint override key 一律使用 Spring MVC 暴露的
 *       <b>路由模板</b>（含 {@code {id}} 占位符），由 {@link RouteTemplateExtractor}
 *       统一从 {@code BEST_MATCHING_PATTERN_ATTRIBUTE} 取得；</li>
 *   <li>路由模板提取失败时（404、静态资源、过滤器链短路等场景），key 退回
 *       原始 URI，同时把 {@code maxRequests} <b>减半</b>作为兜底，避免新模式
 *       失效时仍然能形成防御纵深；</li>
 *   <li>排除路径（{@code rate-limit.exclude-patterns}）<b>不允许</b>覆盖敏感前缀
 *       （登录 / 注册 / 忘记密码 / 重置密码 / WS-ticket / AI），即便运维误把
 *       {@code /api/v1/auth/login} 配进 exclude，敏感路径在拦截器入口仍会
 *       走限流分支。该行为与 {@code SecurityStartupValidator
 *       #validateRateLimitExcludePatterns} 启动期阻断形成"启动期 + 运行时"双重防御。</li>
 *   <li>触发 429 时通过 {@link SecurityMetrics#rateLimit429(String)} 上报路由模板维度
 *       的 Micrometer Counter，避免使用具体 URI 形成高基数指标。</li>
 * </ul>
 * </p>
 *
 * <p>敏感路径（{@link #SENSITIVE_PREFIXES}）走 {@link RedisRateLimiter#tryAcquireFailClosed}
 * fail-closed 分支：Redis 不可用时拒绝请求，避免攻击者借 Redis 抖动绕过限流；
 * 普通读路径走 fail-open，避免限流器自身把整站拖垮。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final TrustedProxyResolver trustedProxyResolver;
    private final RouteTemplateExtractor routeTemplateExtractor;
    private final SecurityMetrics securityMetrics;

    /**
     * 敏感路径前缀列表：命中后限流走 fail-closed 分支，
     * Redis 不可用时拒绝请求而不是放行。
     *
     * <p>同时该列表用于"敏感路径不可被 exclude-patterns 绕过"的运行时校验
     * （见 {@link #isSensitivePath(String)}），与 {@code SecurityStartupValidator}
     * 启动期校验形成双重防御。</p>
     */
    private static final String[] SENSITIVE_PREFIXES = {
            "/api/v1/auth/login",
            "/api/v1/auth/wechat-login",
            "/api/v1/auth/register",
            "/api/v1/auth/email-exists",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/ws-ticket",
            "/api/v1/ai/"
    };

    /**
     * 判断给定路径是否命中敏感前缀。命中即视为"必须走 fail-closed 限流且
     * 不可被 exclude-patterns 绕过"。
     */
    private static boolean isSensitivePath(String path) {
        for (String p : SENSITIVE_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * 判断给定路径是否命中 {@code rate-limit.exclude-patterns}。
     *
     * <p>注意：路径参数 {@code path} 仍使用 {@link HttpServletRequest#getRequestURI()}，
     * 因为运维在 {@code application.yml} 中配置的 exclude pattern 是
     * <b>字面 URI 形式</b>（如 {@code /actuator/**}），与路由模板无关；
     * 这里若改用路由模板会导致历史配置全部失效。</p>
     *
     * @param path 当前请求的字面 URI
     * @return 命中任一 exclude pattern 返回 true
     */
    private boolean isExcluded(String path) {
        for (String pattern : properties.getExcludePatterns()) {
            if (matchPath(path, pattern)) return true;
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled()) return true;

        // 字面 URI 仅用于：1) exclude-patterns 匹配；2) 敏感前缀判断
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 提取路由模板，用于限流 key 与 override 匹配，避免 path variable 形成"按 ID 分桶"的旁路
        RouteTemplateExtractor.ExtractResult extracted = routeTemplateExtractor.extract(request);
        String routeKey = extracted.key();

        // 排除路径检查：但敏感路径即便被加入 exclude 也不放行（双重防御，
        // 与 SecurityStartupValidator.validateRateLimitExcludePatterns 启动期阻断协同）
        if (isExcluded(path) && !isSensitivePath(path)) {
            return true;
        }

        // override key 形如 "POST /api/v1/auth/login" 或 "GET /api/v1/posts/{id}"，
        // 与 application.yml 中按"路由模板"维护的 overrides 一一对应
        String endpointKey = method + " " + routeKey;
        RateLimitProperties.LimitConfig config = properties.getOverrides().get(endpointKey);

        String rateLimitKey;
        if (StpUtil.isLogin()) {
            long userId = StpUtil.getLoginIdAsLong();
            // 已登录：用 userId + 路由模板分桶。N 个不同 ID 的具体请求共享一个桶，
            // 恢复"按端点限流"语义。
            rateLimitKey = "rate_limit:user:" + userId + ":" + routeKey;
            if (config == null) config = properties.getAuthenticated();
        } else {
            // 通过 TrustedProxyResolver 拿到真实 IP，仅在请求来自可信代理时才相信 X-Forwarded-For
            String ip = trustedProxyResolver.resolve(request);
            rateLimitKey = "rate_limit:ip:" + ip + ":" + routeKey;
            if (config == null) config = properties.getAnonymous();
        }

        // 路由模板提取失败兜底：模板缺失时把 max-requests 减半，
        // 避免新模式失效时（例如静态资源、404、过滤器链短路）仍然能形成基本防御。
        if (!extracted.isTemplate()) {
            config = new RateLimitProperties.LimitConfig(
                    Math.max(1, config.getMaxRequests() / 2),
                    config.getWindowSeconds());
        }

        // 敏感路径走 fail-closed 限流，避免 Redis 抖动绕过
        long retryAfter = isSensitivePath(path)
                ? rateLimiter.tryAcquireFailClosed(rateLimitKey, config.getMaxRequests(), config.getWindowSeconds())
                : rateLimiter.tryAcquire(rateLimitKey, config.getMaxRequests(), config.getWindowSeconds());

        if (retryAfter > 0) {
            // 上报路由模板维度的 429 计数（避免具体 URI 形成高基数指标）
            securityMetrics.rateLimit429(routeKey);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\"}");
            return false;
        }

        return true;
    }

    /**
     * 简化的路径模式匹配：仅支持 {@code /xxx/**} 前缀通配与字面相等两种。
     *
     * <p>不引入 Spring AntPathMatcher 是为了避免拦截器热路径上的反射 / 缓存开销；
     * exclude-patterns 数量极少（生产 ≤ 5 条），手工实现即可。</p>
     */
    private boolean matchPath(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.startsWith(prefix);
        }
        return path.equals(pattern);
    }

}
