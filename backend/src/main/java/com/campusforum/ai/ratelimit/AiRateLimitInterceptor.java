package com.campusforum.ai.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.infra.ratelimit.RedisRateLimiter;
import com.campusforum.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * AI 接口分级限流拦截器（model-aware）。
 *
 * <p>三层限流：</p>
 * <ol>
 *   <li>per-user per-minute（默认 5/min）</li>
 *   <li>per-user per-hour by model tier — Pro 模型 10/时，普通模型 20/时</li>
 *   <li>per-tenant per-day（默认 1000/day）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private static final Set<String> PRO_MODELS = Set.of("deepseek-v4-pro", "mimo-v2.5-pro");

    private final RedisRateLimiter rateLimiter;
    private final AiRateLimitProperties props;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!isCostlyAiEndpoint(req)) return true;
        if (!StpUtil.isLogin()) return true;

        long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = TenantContext.getTenantId();

        // 1) per-user per-minute
        String minKey = "ai_rate:user:" + userId + ":min";
        long retryAfter = rateLimiter.tryAcquire(minKey, props.getPerUserPerMin(), 60);
        if (retryAfter > 0) {
            log.info("AI rate limit (user/min): userId={}", userId);
            return reject(res, retryAfter,
                    "AI 调用过于频繁（每分钟最多 " + props.getPerUserPerMin() + " 次）");
        }

        // 2) per-user per-hour by model tier
        String model = extractModel(req);
        boolean isPro = model != null && PRO_MODELS.contains(model);
        String tier = isPro ? "pro" : "normal";
        int limit = isPro ? props.getProPerUserPerHour() : props.getNormalPerUserPerHour();
        String hourKey = "ai_rate:user:" + userId + ":hour:" + tier;
        retryAfter = rateLimiter.tryAcquire(hourKey, limit, 3600);
        if (retryAfter > 0) {
            log.info("AI rate limit (user/hour/{}): userId={}, model={}", tier, userId, model);
            return reject(res, retryAfter,
                    (isPro ? "Pro 模型" : "该模型") + "每小时最多 " + limit + " 次请求，请稍后再试");
        }

        // 3) per-tenant per-day
        if (tenantId != null) {
            String tenantKey = "ai_rate:tenant:" + tenantId + ":day";
            retryAfter = rateLimiter.tryAcquire(tenantKey, props.getPerTenantPerDay(), 86400);
            if (retryAfter > 0) {
                log.info("AI rate limit (tenant): tenantId={}", tenantId);
                return reject(res, retryAfter,
                        "本租户今日 AI 调用已达上限（" + props.getPerTenantPerDay() + " 次）");
            }
        }

        return true;
    }

    /** Extracts the model field from a cached JSON request body. */
    private String extractModel(HttpServletRequest req) {
        if (req instanceof ContentCachingRequestWrapper wrapper) {
            byte[] buf = wrapper.getContentAsByteArray();
            if (buf.length == 0) return null;
            String body = new String(buf, StandardCharsets.UTF_8);
            // Simple JSON field extraction — avoids pulling in Jackson for this one field
            int idx = body.indexOf("\"model\"");
            if (idx < 0) return null;
            int colon = body.indexOf(':', idx);
            if (colon < 0) return null;
            int start = body.indexOf('"', colon);
            if (start < 0) return null;
            int end = body.indexOf('"', start + 1);
            if (end < 0) return null;
            return body.substring(start + 1, end);
        }
        return null;
    }

    private boolean isCostlyAiEndpoint(HttpServletRequest req) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) return false;
        String path = req.getRequestURI();
        return path.equals("/api/v1/ai/chat")
                || path.equals("/api/v1/ai/rag-chat")
                || path.equals("/api/v1/ai/summarize")
                || path.equals("/api/v1/ai/moderate")
                || path.equals("/api/v1/ai/tags")
                || path.matches("/api/v1/ai/post-card/[^/]+")
                || path.matches("/api/v1/ai/conversations/[^/]+/messages");
    }

    private boolean reject(HttpServletResponse res, long retryAfter, String msg) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":42900,\"message\":\"" + msg + "\",\"data\":null}");
        return false;
    }
}
