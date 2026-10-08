package com.campusforum.search.service;

import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MeiliSearch HTTP 客户端。
 *
 * <p>安全加固：写入文档时自动注入 {@code tenantId} 字段；搜索时强制要求
 * 调用方传入 {@code tenantId} 并自动追加 {@code filter: "tenantId = X"}，
 * 避免 multi 模式下跨租户数据泄漏。索引创建时自动设置
 * {@code filterableAttributes: ["tenantId"]}。</p>
 *
 * <p>本类对应 bugfix.md 漏洞 22 的"目标状态 — MeiliSearchClient.search
 * tenantId 强制"：当 {@code tenantId == null} 时，<b>拒绝执行</b>该次搜索
 * 并返回空列表，同时通过 {@link SecurityMetrics#tenantViolation(String)}
 * 累加 {@code tenant_violation_total{reason="missing_tenant_in_search"}}
 * Counter，便于运维识别"哪些代码路径仍漏过了租户上下文"。</p>
 *
 * <p>历史 3 参重载 {@code search(String, String, int)} 已被删除（漏洞 22
 * 设计要求）：调用方必须在编译期就把租户 ID 显式传进来，避免依赖
 * {@link TenantContext#getTenantId()} 兜底——异步线程或子组件中
 * ThreadLocal 可能为空，那种"静默不加 filter"的退化是漏洞根因。</p>
 */
@Slf4j
@Component
public class MeiliSearchClient {

    private final RestTemplate restTemplate;
    // ObjectMapper 线程安全且无实例级配置，按类共享单例
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final String host;
    private final String apiKey;
    private final boolean active;
    /** 安全监控埋点：用于上报"搜索缺租户上下文"等跨租户违规事件。 */
    private final SecurityMetrics securityMetrics;

    /** 已配置过 filterableAttributes 的索引缓存，避免每次写入都打配置接口。 */
    private final Map<String, Boolean> filterableConfigured = new ConcurrentHashMap<>();

    /**
     * 构造函数。
     *
     * <p>除了原有的 {@code search.*} 配置项注入外，新增对
     * {@link SecurityMetrics} 的依赖：{@link #search(String, String, int, Long)}
     * 在租户上下文缺失时需要上报 {@code tenant_violation_total} Counter。</p>
     *
     * @param type            搜索引擎类型（{@code mysql} / {@code meilisearch}）
     * @param host            MeiliSearch HTTP 端点
     * @param apiKey          MeiliSearch API Key
     * @param securityMetrics 安全监控埋点组件（不可为 null）
     */
    public MeiliSearchClient(@Value("${search.type:mysql}") String type,
                             @Value("${search.meilisearch.host:http://localhost:7700}") String host,
                             @Value("${search.meilisearch.api-key:}") String apiKey,
                             SecurityMetrics securityMetrics) {
        this.host = host;
        this.apiKey = apiKey;
        this.active = "meilisearch".equals(type);
        this.restTemplate = new RestTemplate();
        this.securityMetrics = securityMetrics;
    }

    /**
     * 在指定索引中搜索；强制要求传入 {@code tenantId} 以追加 tenantId filter。
     *
     * <p>调用方传入的 {@code tenantId} 应来自服务端权威来源（{@link TenantContext}
     * 或 Sa-Token Session），<b>禁止</b>来自客户端请求体——后者可被攻击者
     * 替换为任意值绕过租户隔离。</p>
     *
     * <p>当 {@code tenantId == null} 时本方法<b>不会</b>执行搜索，而是：
     * <ol>
     *   <li>输出 ERROR 级日志，便于运维定位漏过租户上下文的调用栈；</li>
     *   <li>调用 {@link SecurityMetrics#tenantViolation(String)} 累加
     *       {@code reason="missing_tenant_in_search"} 计数，便于 Grafana
     *       告警；</li>
     *   <li>返回空列表 {@link List#of()}（拒绝放行而非静默全租户聚合检索）。</li>
     * </ol>
     * 这是对 bugfix.md 漏洞 22"MeiliSearch tenantId 缺失静默放行"的根因修复。</p>
     *
     * @param index    MeiliSearch 索引名，如 {@code posts} / {@code resources}
     * @param query    用户原始关键字（已由上层做过长度截断 / 净化）
     * @param limit    返回结果上限
     * @param tenantId 当前租户 ID；缺失即拒绝搜索
     * @return 命中文档列表；不可用、缺租户、异常时一律返回空列表
     */
    public List<Map<String, Object>> search(String index, String query, int limit, Long tenantId) {
        if (!active) return List.of();
        // 漏洞 22：拒绝在 tenantId 缺失时执行搜索。返回空列表 + 上报 metrics
        // 取代原来"不追加 filter 即跨租户聚合检索"的静默放行行为
        if (tenantId == null) {
            log.error("MeiliSearch search called without tenantId, refusing to search index={}", index);
            securityMetrics.tenantViolation("missing_tenant_in_search");
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("q", query);
            body.put("limit", limit);
            // tenantId 已经在上面校验非空，这里恒会追加 filter
            body.put("filter", "tenantId = " + tenantId);
            Map resp = post("/indexes/" + index + "/search", body);
            List<Map<String, Object>> hits = (List<Map<String, Object>>) resp.get("hits");
            return hits != null ? hits : List.of();
        } catch (Exception e) {
            log.debug("MeiliSearch search failed for index={}: {}", index, e.getMessage());
            return List.of();
        }
    }

    public void indexDocuments(String index, List<Map<String, Object>> documents) {
        if (!active || documents.isEmpty()) return;
        try {
            ensureIndex(index);
            // 写入前为每条文档注入 tenantId（若调用方未显式提供）
            Long ctxTenant = TenantContext.getTenantId();
            for (Map<String, Object> doc : documents) {
                if (!doc.containsKey("tenantId") && ctxTenant != null) {
                    doc.put("tenantId", ctxTenant);
                }
            }
            post("/indexes/" + index + "/documents", documents);
            log.debug("Indexed {} documents to {}", documents.size(), index);
        } catch (Exception e) {
            log.debug("MeiliSearch index failed for index={}: {}", index, e.getMessage());
        }
    }

    public void indexDocument(String index, Map<String, Object> document) {
        indexDocuments(index, List.of(document));
    }

    public void deleteDocument(String index, Long id) {
        if (!active) return;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) headers.set("Authorization", "Bearer " + apiKey);
            restTemplate.exchange(host + "/indexes/" + index + "/documents/" + id,
                    HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        } catch (Exception e) {
            log.debug("MeiliSearch delete failed for index={}, id={}: {}", index, id, e.getMessage());
        }
    }

    /**
     * 确保索引存在，并已配置 tenantId 为可过滤属性。
     */
    private void ensureIndex(String index) {
        boolean indexExisted = false;
        try {
            HttpHeaders headers = jsonHeaders();
            ResponseEntity<String> getResp = restTemplate.exchange(
                    host + "/indexes/" + index, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            indexExisted = getResp.getStatusCode().is2xxSuccessful();
        } catch (Exception ignored) {
        }
        if (!indexExisted) {
            try {
                Map<String, Object> body = Map.of("uid", index, "primaryKey", "id");
                restTemplate.exchange(host + "/indexes", HttpMethod.POST,
                        new HttpEntity<>(body, jsonHeaders()), Map.class);
                log.info("Created MeiliSearch index: {}", index);
            } catch (Exception e) {
                log.debug("Failed to create MeiliSearch index {}: {}", index, e.getMessage());
            }
        }
        configureFilterableAttributes(index);
    }

    /**
     * 把 tenantId 注册为可过滤属性。索引启动期一次性下发，缓存避免重复请求。
     */
    private void configureFilterableAttributes(String index) {
        if (Boolean.TRUE.equals(filterableConfigured.get(index))) return;
        try {
            HttpEntity<List<String>> entity = new HttpEntity<>(List.of("tenantId"), jsonHeaders());
            restTemplate.exchange(
                    host + "/indexes/" + index + "/settings/filterable-attributes",
                    HttpMethod.PUT, entity, String.class);
            filterableConfigured.put(index, Boolean.TRUE);
            log.info("MeiliSearch filterable-attributes configured for index={}", index);
        } catch (Exception e) {
            log.debug("Failed to configure filterable-attributes for {}: {}", index, e.getMessage());
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private Map post(String path, Object body) {
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(host + path, HttpMethod.POST,
                    new HttpEntity<>(body, jsonHeaders()), Map.class);
            return resp.getBody();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
