package com.campusforum.ai.ratelimit;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * 对 AI 端点请求体做缓存，允许下游拦截器解析 model 字段用于分级限流。
 * 不影响常规请求的性能（仅 /api/v1/ai/ 路径生效）。
 */
@Component
@Order(-100)
public class AiRequestBodyCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (req instanceof HttpServletRequest hreq) {
            String path = hreq.getRequestURI();
            if (path.startsWith("/api/v1/ai/") && "POST".equalsIgnoreCase(hreq.getMethod())) {
                req = new ContentCachingRequestWrapper(hreq);
            }
        }
        chain.doFilter(req, res);
    }
}
