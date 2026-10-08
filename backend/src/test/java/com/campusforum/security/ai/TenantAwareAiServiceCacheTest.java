package com.campusforum.security.ai;

import com.campusforum.ai.config.AiProviderProperties;
import com.campusforum.ai.service.AiService;
import com.campusforum.ai.service.MockAiService;
import com.campusforum.ai.service.OpenAiCompatService;
import com.campusforum.ai.service.TenantAwareAiService;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.audit.AuditContext;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.CryptoException;
import com.campusforum.infra.security.TrustedProxyResolver;
import com.campusforum.tenant.TenantContext;
import com.campusforum.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：{@link TenantAwareAiService} 按租户缓存 OpenAI 客户端 + fail-loud 行为。
 *
 * <p>对应任务 T7.1 + T7.2 + T7.3，关联 bugfix.md 漏洞 12（OpenAiCompatService 全局
 * 凭证 Bean + 每请求 new + 解密失败静默降级 mock）。</p>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>不启动 Spring 容器：用 Mockito 直接构造 {@link TenantAwareAiService}，
 *       通过反射读取私有 {@code clientCache} 字段，断言缓存命中 / 重建 / evict
 *       三种状态；</li>
 *   <li>不会真的发起 HTTP 请求：测试只关心 delegate 选哪个 AiService 实例与
 *       缓存表的指针变化，{@link OpenAiCompatService} 的 {@code chatCompletion}
 *       链路在本测试中不会被触达；</li>
 *   <li>{@link TenantContext} 在每个用例 {@link BeforeEach} 设置 / {@link AfterEach}
 *       清理，避免污染其他测试。</li>
 * </ul>
 */
class TenantAwareAiServiceCacheTest {

    /** 被测对象。 */
    private TenantAwareAiService service;

    /** Mock 协作者。 */
    private TenantService tenantService;
    private MockAiService mockAiService;
    private AuditLogService auditLogService;
    private SecurityMetrics securityMetrics;
    private TrustedProxyResolver trustedProxyResolver;
    private AiProviderProperties aiProviderProperties;
    private HttpServletRequest httpRequest;

    /** 测试常量：默认租户与 AI 配置。 */
    private static final long TENANT_ID = 7L;
    private static final String OPENAI_PROVIDER = "openai";
    private static final String VALID_BASE_URL = "https://api.deepseek.com/v1";
    private static final String VALID_API_KEY = "sk-fake-test-key-001";
    private static final String VALID_MODEL = "deepseek-v4-flash";

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        mockAiService = mock(MockAiService.class);
        auditLogService = mock(AuditLogService.class);
        securityMetrics = mock(SecurityMetrics.class);
        trustedProxyResolver = mock(TrustedProxyResolver.class);
        aiProviderProperties = new AiProviderProperties();
        httpRequest = mock(HttpServletRequest.class);

        service = new TenantAwareAiService(
                tenantService,
                mockAiService,
                auditLogService,
                securityMetrics,
                trustedProxyResolver,
                aiProviderProperties,
                new MockEnvironment(),
                httpRequest);

        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 构造一份合法的 openai 配置 map。 */
    private Map<String, String> validOpenAiConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("provider", OPENAI_PROVIDER);
        config.put("baseUrl", VALID_BASE_URL);
        config.put("apiKey", VALID_API_KEY);
        config.put("model", VALID_MODEL);
        return config;
    }

    /**
     * 通过反射读取私有 {@code clientCache} 字段，便于断言缓存内容。
     * <p>使用反射的原因：缓存是实现细节，没有也不应该暴露 public getter；
     * 单测内部通过反射可以保留实现的最小封装面。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<Long, ?> readClientCache() throws Exception {
        Field f = TenantAwareAiService.class.getDeclaredField("clientCache");
        f.setAccessible(true);
        return (Map<Long, ?>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> readProviderClientCache() throws Exception {
        Field f = TenantAwareAiService.class.getDeclaredField("providerClientCache");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(service);
    }

    /** 通过反射拿到 AiClientHolder 中的 OpenAiCompatService 实例引用。 */
    private OpenAiCompatService unwrapClient(Object holder) throws Exception {
        // record 自动生成 client() 访问器；AiClientHolder 是私有嵌套记录，
        // 即使方法是 public 也需要 setAccessible(true) 才能跨包反射调用
        java.lang.reflect.Method m = holder.getClass().getMethod("client");
        m.setAccessible(true);
        return (OpenAiCompatService) m.invoke(holder);
    }

    private AiService invokeModelDelegate(String model) throws Exception {
        java.lang.reflect.Method m = TenantAwareAiService.class.getDeclaredMethod("delegate", String.class);
        m.setAccessible(true);
        return (AiService) m.invoke(service, model);
    }

    /**
     * 调用一个无副作用的接口方法触发 delegate()。这里选 {@code summarize("")}：
     * 实现里如果是 mock 会立即返回空串；如果是 OpenAiCompatService，对空内容
     * 也会快速返回 ""（不会真的发 HTTP 请求），保证测试不会因为外部网络中断而失败。
     */
    private void invokeDelegate() {
        service.summarize("");
    }

    @Test
    @DisplayName("同租户同配置：连续两次调用复用同一 OpenAiCompatService 实例（缓存命中）")
    void sameTenant_sameConfig_reusesClient() throws Exception {
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(validOpenAiConfig());

        invokeDelegate();
        Map<Long, ?> cache1 = readClientCache();
        assertThat(cache1).containsKey(TENANT_ID);
        OpenAiCompatService first = unwrapClient(cache1.get(TENANT_ID));

        invokeDelegate();
        Map<Long, ?> cache2 = readClientCache();
        OpenAiCompatService second = unwrapClient(cache2.get(TENANT_ID));

        // 关键断言：是同一个对象引用，没有重建
        assertThat(second).isSameAs(first);

        // mockAiService 不应该被路由到（provider=openai + apiKey 非空）
        verify(mockAiService, never()).summarize(anyString());
    }

    @Test
    @DisplayName("配置指纹变化（model 改变）：缓存被重建，新旧实例不是同一引用")
    void configChanged_evictsAndRebuilds_byFingerprint() throws Exception {
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(validOpenAiConfig());

        invokeDelegate();
        OpenAiCompatService firstClient = unwrapClient(readClientCache().get(TENANT_ID));

        // 第二次返回不同 model，触发指纹变化（map 是 final 实例，需要返回一个新 map）
        Map<String, String> changed = validOpenAiConfig();
        changed.put("model", "deepseek-coder");
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(changed);

        invokeDelegate();
        OpenAiCompatService secondClient = unwrapClient(readClientCache().get(TENANT_ID));

        assertThat(secondClient).isNotSameAs(firstClient);
    }

    @Test
    @DisplayName("evict(tenantId)：清空对应租户缓存，下次调用重建新实例")
    void evict_clearsCache_andRebuildsOnNextCall() throws Exception {
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(validOpenAiConfig());

        invokeDelegate();
        OpenAiCompatService firstClient = unwrapClient(readClientCache().get(TENANT_ID));

        // 主动 evict
        service.evict(TENANT_ID);
        assertThat(readClientCache()).doesNotContainKey(TENANT_ID);

        // 即使后续配置没变，evict 后下一次调用也必须重建（因为缓存已清空）
        invokeDelegate();
        OpenAiCompatService secondClient = unwrapClient(readClientCache().get(TENANT_ID));

        assertThat(secondClient).isNotSameAs(firstClient);
    }

    @Test
    @DisplayName("选择模型但全局 provider 未配置 key：回退到租户 AI 配置，并按所选模型创建客户端")
    void selectedModel_withoutProviderKey_usesTenantConfigWithRequestedModel() throws Exception {
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(validOpenAiConfig());

        AiService selected = invokeModelDelegate("deepseek-v4-flash");

        assertThat(selected).isInstanceOf(OpenAiCompatService.class);
        assertThat(readProviderClientCache()).containsKey("tenant:" + TENANT_ID + ":deepseek-v4-flash");
        verify(mockAiService, never()).chat(any(), anyString());
    }

    @Test
    @DisplayName("evict(tenantId)：同时清理按所选模型创建的租户 AI 客户端缓存")
    void evict_clearsSelectedModelTenantCache() throws Exception {
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(validOpenAiConfig());

        invokeModelDelegate("deepseek-v4-flash");
        assertThat(readProviderClientCache()).containsKey("tenant:" + TENANT_ID + ":deepseek-v4-flash");

        service.evict(TENANT_ID);

        assertThat(readProviderClientCache()).doesNotContainKey("tenant:" + TENANT_ID + ":deepseek-v4-flash");
    }

    @Test
    @DisplayName("解密失败：抛 BusinessException(AI_SERVICE_UNAVAILABLE) + 写 AI_DECRYPT_FAIL 审计 + 监控埋点")
    void decryptFail_throwsAiUnavailable_andAudits() {
        // 模拟 resolveAiCredentials 解密时抛 CryptoException
        when(tenantService.resolveAiCredentials(TENANT_ID))
                .thenThrow(new CryptoException("legacy ECB ciphertext corrupted"));

        // 走 fail-loud 路径：抛 BusinessException(AI_SERVICE_UNAVAILABLE)
        assertThatThrownBy(this::invokeDelegate)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException biz = (BusinessException) ex;
                    assertThat(biz.getCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode());
                });

        // 审计写入 + 监控埋点都要被调用
        verify(auditLogService, times(1)).log(
                any(AuditContext.class),
                eq("AI_DECRYPT_FAIL"),
                eq("tenant"),
                eq(TENANT_ID),
                anyString());
        verify(securityMetrics, times(1)).cryptoDecryptFailed();

        // 不能走 mock 降级（fail-loud 与 mock 互斥）
        verify(mockAiService, never()).summarize(anyString());
    }

    @Test
    @DisplayName("provider != openai：直接走 mockAiService，不触发缓存")
    void nonOpenaiProvider_fallsBackMock_noCache() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("provider", "mock");
        config.put("baseUrl", "");
        config.put("apiKey", "");
        config.put("model", "");
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(config);
        when(mockAiService.summarize("")).thenReturn("");

        invokeDelegate();

        verify(mockAiService, times(1)).summarize("");
        // mock 路径不应该写缓存
        assertThat(readClientCache()).doesNotContainKey(TENANT_ID);
        verify(securityMetrics, never()).ssrfBlocked(anyString());
    }

    @Test
    @DisplayName("apiKey 为空：直接走 mockAiService，不触发缓存")
    void emptyApiKey_fallsBackMock() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("provider", OPENAI_PROVIDER);
        config.put("baseUrl", VALID_BASE_URL);
        config.put("apiKey", "");
        config.put("model", VALID_MODEL);
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(config);
        when(mockAiService.summarize("")).thenReturn("");

        invokeDelegate();

        verify(mockAiService, times(1)).summarize("");
        assertThat(readClientCache()).doesNotContainKey(TENANT_ID);
    }

    @Test
    @DisplayName("baseUrl 命中私网（http://127.0.0.1）：走 mock + 写 AI_SSRF_BLOCKED 审计 + ssrf 监控埋点")
    void privateNetworkBaseUrl_fallsBackMock_andAudits() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("provider", OPENAI_PROVIDER);
        config.put("baseUrl", "http://127.0.0.1:8080");  // requirePublic 会拒绝
        config.put("apiKey", VALID_API_KEY);
        config.put("model", VALID_MODEL);
        when(tenantService.resolveAiCredentials(TENANT_ID)).thenReturn(config);
        when(mockAiService.summarize("")).thenReturn("");

        invokeDelegate();

        // 走 mock 降级
        verify(mockAiService, times(1)).summarize("");
        assertThat(readClientCache()).doesNotContainKey(TENANT_ID);

        // 审计 + 监控埋点
        verify(auditLogService, times(1)).log(
                any(AuditContext.class),
                eq("AI_SSRF_BLOCKED"),
                eq("tenant"),
                eq(TENANT_ID),
                anyString());
        verify(securityMetrics, times(1)).ssrfBlocked(eq("validator"));
    }

    @Test
    @DisplayName("没有 tenantId（standalone 模式）：直接走 mockAiService，不解析配置")
    void noTenantId_fallsBackMock_withoutResolvingCredentials() {
        TenantContext.clear();
        when(mockAiService.summarize("")).thenReturn("");

        invokeDelegate();

        verify(mockAiService, times(1)).summarize("");
        // 关键：不应触发 resolveAiCredentials，避免 standalone 模式下报错
        verify(tenantService, never()).resolveAiCredentials(anyLong());
    }
}
