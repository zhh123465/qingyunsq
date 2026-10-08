package com.campusforum.security.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.ratelimit.RateLimitInterceptor;
import com.campusforum.infra.ratelimit.RateLimitProperties;
import com.campusforum.infra.ratelimit.RedisRateLimiter;
import com.campusforum.infra.ratelimit.RouteTemplateExtractor;
import com.campusforum.infra.security.TrustedProxyResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 路由模板改造单元测试（任务 T5.2）。
 *
 * <p>对应 bugfix.md 漏洞 7（限流 key 含 path variable）的修复验证：</p>
 * <ul>
 *   <li>不同 path variable 的具体 URI（如 {@code /api/v1/posts/1} 与
 *       {@code /api/v1/posts/2}）必须共享同一限流桶（路由模板
 *       {@code /api/v1/posts/{id}} 一致即一桶）；</li>
 *   <li>路由模板提取失败时（404 / 静态资源 / 过滤器链短路）走"配额减半"兜底；</li>
 *   <li>已有 {@code overrides}（如 {@code POST /api/v1/auth/login}）继续命中，
 *       且敏感路径走 {@link RedisRateLimiter#tryAcquireFailClosed} 分支；</li>
 *   <li>触发 429 时 {@link SecurityMetrics#rateLimit429(String)} 上报路由模板
 *       维度的 Counter；</li>
 *   <li>敏感路径即便被运维误加入 exclude-patterns，运行时仍走限流而非放行；</li>
 *   <li>非敏感路径命中 exclude-patterns 时 short-circuit 返回 true，
 *       {@link RedisRateLimiter} 不被调用。</li>
 * </ul>
 *
 * <p>测试一律使用 Mockito mock 协作组件 + Spring 提供的 {@link MockHttpServletRequest}
 * 模拟 servlet 上下文，无需启动 Spring 容器，运行速度快且与生产代码隔离。</p>
 */
class RateLimitInterceptorRouteTemplateTest {

    private RedisRateLimiter rateLimiter;
    private RateLimitProperties properties;
    private TrustedProxyResolver trustedProxyResolver;
    private RouteTemplateExtractor routeTemplateExtractor;
    private SecurityMetrics securityMetrics;
    private RateLimitInterceptor interceptor;

    /** Sa-Token 静态方法 mock 句柄，每个测试独立 setup/tearDown 以避免线程间污染。 */
    private MockedStatic<StpUtil> stpUtilMock;

    private static final long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisRateLimiter.class);
        properties = new RateLimitProperties();
        // 使用真实 RateLimitProperties + 真实默认 LimitConfig（200/60、100/60），便于断言"减半"语义
        properties.setEnabled(true);
        // 显式覆盖默认 excludePatterns，避免 RateLimitProperties.excludePatterns 默认值带来的副作用
        properties.setExcludePatterns(List.of("/actuator/**"));
        properties.setOverrides(new HashMap<>());

        trustedProxyResolver = mock(TrustedProxyResolver.class);
        // 默认匿名分支 fallback 到 IP 解析；少数用例不依赖该路径，使用 lenient 避免 strict mode 报错
        lenient().when(trustedProxyResolver.resolve(any())).thenReturn("127.0.0.1");

        routeTemplateExtractor = mock(RouteTemplateExtractor.class);
        // 使用 SimpleMeterRegistry 真实计数，便于断言上报内容
        securityMetrics = new SecurityMetrics(new SimpleMeterRegistry());

        interceptor = new RateLimitInterceptor(
                rateLimiter, properties, trustedProxyResolver,
                routeTemplateExtractor, securityMetrics);

        stpUtilMock = mockStatic(StpUtil.class);
        // 默认未登录，单测内按需覆盖
        stpUtilMock.when(StpUtil::isLogin).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
    }

    /**
     * 构造一个携带"路由模板 attribute"的请求。模拟 Spring MVC DispatcherServlet
     * 在路由匹配阶段写入 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 的常规路径。
     */
    private MockHttpServletRequest req(String method, String uri, String pattern) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (pattern != null) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        return req;
    }

    @Test
    @DisplayName("不同 path variable 的请求共享同一限流桶（路由模板一致 → 限流 key 一致）")
    void postsId_sharesBucket_acrossDifferentIds() throws Exception {
        // 已登录用户连续访问 /api/v1/posts/1 与 /api/v1/posts/2，路由模板均为 /api/v1/posts/{id}
        stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/posts/{id}", true));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(0L);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(req("GET", "/api/v1/posts/1", "/api/v1/posts/{id}"), resp, new Object());
        interceptor.preHandle(req("GET", "/api/v1/posts/2", "/api/v1/posts/{id}"), resp, new Object());

        // 两次请求传给 rateLimiter 的 key 必须完全一致（共享同桶），key 中不出现具体 ID
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryAcquire(keyCap.capture(), anyInt(), anyInt());
        List<String> keys = keyCap.getAllValues();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        assertThat(keys.get(0)).isEqualTo("rate_limit:user:" + USER_ID + ":/api/v1/posts/{id}");
        // key 中不能泄漏具体的 path variable 值
        assertThat(keys.get(0)).doesNotContain("/posts/1").doesNotContain("/posts/2");
    }

    @Test
    @DisplayName("路由模板提取失败时 max-requests 减半作为兜底")
    void fallback_when_templateMissing_halvesMax() throws Exception {
        // 匿名访问、模板缺失（isTemplate=false） → 默认匿名配置 100 / 60s 应被减半为 50
        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/unknown", false));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(0L);

        interceptor.preHandle(req("GET", "/api/v1/unknown", null), new MockHttpServletResponse(), new Object());

        ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> windowCap = ArgumentCaptor.forClass(Integer.class);
        verify(rateLimiter).tryAcquire(anyString(), maxCap.capture(), windowCap.capture());

        // 默认 anonymous=100/min，兜底减半应为 50
        assertThat(maxCap.getValue()).isEqualTo(50);
        assertThat(windowCap.getValue()).isEqualTo(60);
    }

    @Test
    @DisplayName("现有 overrides（如 POST /api/v1/auth/login）仍按路由模板命中且走 fail-closed 分支")
    void existing_overrides_still_match() throws Exception {
        // 在 overrides 中显式注册登录端点的特殊配额（与 application.yml 行为对齐）
        Map<String, RateLimitProperties.LimitConfig> overrides = new HashMap<>();
        overrides.put("POST /api/v1/auth/login", new RateLimitProperties.LimitConfig(5, 60));
        properties.setOverrides(overrides);

        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/auth/login", true));
        when(rateLimiter.tryAcquireFailClosed(anyString(), anyInt(), anyInt())).thenReturn(0L);

        interceptor.preHandle(
                req("POST", "/api/v1/auth/login", "/api/v1/auth/login"),
                new MockHttpServletResponse(), new Object());

        // 敏感路径必须走 fail-closed 分支；override 配置 5/60 应被透传
        verify(rateLimiter).tryAcquireFailClosed(anyString(), eq(5), eq(60));
        // 敏感路径不应错走 fail-open 分支
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("触发 429 时 SecurityMetrics.rateLimit429 上报路由模板（非具体 URI）")
    void rateLimit429_metrics_recorded() throws Exception {
        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/posts/{id}", true));
        // mock 限流命中：tryAcquire 返回正数 retryAfter（秒）
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(7L);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(
                req("GET", "/api/v1/posts/42", "/api/v1/posts/{id}"), resp, new Object());

        // 拦截器返回 false + 写入 429 / Retry-After
        assertThat(ok).isFalse();
        // jakarta.servlet 6.x 没有 SC_TOO_MANY_REQUESTS 常量，直接断言字面量 429
        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getHeader("Retry-After")).isEqualTo("7");

        // Counter 必须按路由模板而非具体 URI 上报，避免高基数
        var counter = ((SimpleMeterRegistry) findRegistry())
                .find("rate_limit_429").tag("route", "/api/v1/posts/{id}").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("敏感路径即便被加入 exclude-patterns 也不被绕过（双重防御）")
    void excludePath_butSensitive_notSkipped() throws Exception {
        // 模拟运维误把 /api/v1/auth/login 加入 exclude（即便启动校验放过这一情况）
        properties.setExcludePatterns(List.of("/api/v1/auth/login"));

        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/auth/login", true));
        when(rateLimiter.tryAcquireFailClosed(anyString(), anyInt(), anyInt())).thenReturn(0L);

        boolean ok = interceptor.preHandle(
                req("POST", "/api/v1/auth/login", "/api/v1/auth/login"),
                new MockHttpServletResponse(), new Object());

        // 即便命中 exclude，敏感路径仍要进入限流分支
        assertThat(ok).isTrue();
        verify(rateLimiter, atLeastOnce())
                .tryAcquireFailClosed(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("微信登录路径即便被加入 exclude-patterns 也不被绕过")
    void excludePath_butWechatLoginSensitive_notSkipped() throws Exception {
        properties.setExcludePatterns(List.of("/api/v1/auth/wechat-login"));

        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/api/v1/auth/wechat-login", true));
        when(rateLimiter.tryAcquireFailClosed(anyString(), anyInt(), anyInt())).thenReturn(0L);

        boolean ok = interceptor.preHandle(
                req("POST", "/api/v1/auth/wechat-login", "/api/v1/auth/wechat-login"),
                new MockHttpServletResponse(), new Object());

        assertThat(ok).isTrue();
        verify(rateLimiter, atLeastOnce())
                .tryAcquireFailClosed(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("非敏感路径命中 exclude-patterns 时直接放行，rateLimiter 不被调用")
    void excludePath_nonSensitive_skipped() throws Exception {
        properties.setExcludePatterns(List.of("/actuator/**"));
        // 即便 extractor 返回 isTemplate=false 也不应触发限流（exclude 优先短路）
        when(routeTemplateExtractor.extract(any()))
                .thenReturn(new RouteTemplateExtractor.ExtractResult("/actuator/health", false));

        boolean ok = interceptor.preHandle(
                req("GET", "/actuator/health", null),
                new MockHttpServletResponse(), new Object());

        assertThat(ok).isTrue();
        // exclude 短路，限流器一次都不应被调用
        verifyNoInteractions(rateLimiter);
    }

    /**
     * 反射读取 SecurityMetrics 持有的 MeterRegistry，避免修改生产代码 API。
     *
     * <p>{@code SecurityMetrics} 的实现是直接把 MeterRegistry 注册新 Counter，
     * 因此可以从同一注册表读回 Counter 做断言。</p>
     */
    private Object findRegistry() throws Exception {
        var field = SecurityMetrics.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        return field.get(securityMetrics);
    }
}
