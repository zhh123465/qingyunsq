package com.campusforum.security;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    static final String[] PUBLIC_AUTH_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/wechat-login",
            "/api/v1/auth/github-login",
            "/api/v1/auth/register",
            "/api/v1/auth/email-code",
            "/api/v1/auth/email-exists",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 对 /api/v1/** 路由应用判定
            SaRouter.match("/api/v1/**")
                    // 放行认证相关接口
                    .notMatch(PUBLIC_AUTH_PATHS)
                    // 放行租户和直连文件下载/预览接口，以及游客能调用的无副作用 POST 接口
                    .notMatch(
                            "/api/v1/tenant/info",
                            "/api/v1/resources/*/download",
                            "/api/v1/resources/*/preview",
                            "/api/v1/ai/post-cards/batch"
                    )
                    .check(r -> {
                        String path = SaHolder.getRequest().getRequestPath();
                        String method = SaHolder.getRequest().getMethod();

                        if ("GET".equalsIgnoreCase(method)) {
                            // 排除游客无权访问的敏感 GET 接口，它们必须登录
                            if (path.startsWith("/api/v1/auth/me") ||
                                path.startsWith("/api/v1/notifications") ||
                                path.startsWith("/api/v1/messages") ||
                                path.startsWith("/api/v1/resources/mine") ||
                                path.contains("/follow")) {
                                StpUtil.checkLogin();
                            }
                        } else {
                            // 所有写操作（POST, PUT, DELETE, PATCH）均强制要求登录
                            StpUtil.checkLogin();
                        }
                    });
        })).addPathPatterns("/api/v1/**");
    }
}
