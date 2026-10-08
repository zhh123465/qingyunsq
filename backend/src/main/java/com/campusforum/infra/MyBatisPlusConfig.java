package com.campusforum.infra;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.campusforum.tenant.TenantContext;
import com.campusforum.tenant.TenantContextMissingException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class MyBatisPlusConfig {

    /**
     * 不需要做租户隔离的系统表/字典表。
     *
     * <p>暴露为 {@code public} 不可变常量，供 {@link com.campusforum.tenant.TenantStartupValidator}
     * 在启动期做 schema 校验：</p>
     * <ul>
     *   <li>列在该集合中的表必须确实没有 {@code tenant_id} 列，否则视为"应做租户隔离的业务表被误加入忽略名单"，对应 bugfix.md 漏洞 14；</li>
     *   <li>未列在该集合中且不属于 flyway/schema 元数据的业务表，应该都拥有 {@code tenant_id} 列。</li>
     * </ul>
     *
     * <p><b>2026-06-01 调整（spec security-audit-hardening T6.1 / T8.5）</b>：
     * 原集合包含 {@code "sensitive_words"}，但 T8.5 将敏感词改为支持按租户分桶
     * （每个租户独立维护黑名单 + 风险等级），表 DDL 上 {@code tenant_id NOT NULL}。
     * 同时把它放在忽略名单 + DDL 留 tenant_id 会被 {@code TenantStartupValidator}
     * 视为"业务表被误加入忽略名单"并阻断启动，因此从忽略名单中移除。
     * 真正需要忽略的是 {@code tenants}（租户表自身）与 {@code achievements}（全局字典）。</p>
     */
    public static final Set<String> TENANT_IGNORE_TABLES = Set.of(
            "tenants", "achievements",
            // AI 工作台子表：租户由其父表（ai_knowledge_bases / ai_conversations / users）的 tenant_id 决定，
            // 本身不设 tenant_id 列，以免出现子表与父表 tenant_id 不一致导致越权读取。
            "ai_kb_documents", "ai_kb_qa_pairs", "ai_ingest_tasks", "ai_kb_chunks",
            "ai_messages", "ai_user_favorites",
            // 学习教程为全站共享公益内容（抓取自公开来源），对所有租户/用户一致，不做租户隔离。
            "learning_tutorials", "learning_lessons"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户 SQL 自动改写：始终注册，由 TenantContext.getTenantId() 决定运行期 tenant_id。
        // - standalone 模式：所有请求 TenantContext = standaloneTenantId，写入 SQL 的 tenant_id = 该值
        // - multi 模式：TenantContext 由 TenantResolutionFilter 根据 Sa-Token Session/子域名/X-Tenant-Id 解析得到
        // - TenantContext 为 null 时：直接抛 TenantContextMissingException（任务 T9.4 / 漏洞 28）
        //   由 GlobalExceptionHandler 翻译为 503 SERVICE_UNAVAILABLE；禁止静默降级。
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.getTenantId();
                if (tenantId == null) {
                    throw new TenantContextMissingException(
                        "MyBatisPlusConfig#getTenantId — missing TenantResolutionFilter "
                        + "or an entry path bypassed it (e.g., scheduled task without explicit TenantContext setup).");
                }
                return new LongValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TENANT_IGNORE_TABLES.contains(tableName);
            }
        }));

        // 分页插件
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
