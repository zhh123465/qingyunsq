package com.campusforum.ai.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 接口业务限流配置，绑定 application.yml 中的 ai.rate-limit 节。
 *
 * <p>区别于通用基础设施限流（RateLimitProperties），这里仅约束 /api/v1/ai/** 路径，
 * 目的是控制 AI 推理成本（按 per-user/min 防滥用 + per-tenant/day 防超预算）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rate-limit")
public class AiRateLimitProperties {

    /** 每用户每分钟最大 AI 调用次数 */
    private int perUserPerMin = 5;

    /** Pro 模型每用户每小时最大请求次数 */
    private int proPerUserPerHour = 10;

    /** 普通模型每用户每小时最大请求次数 */
    private int normalPerUserPerHour = 20;

    /** 每租户每天最大 AI 调用次数 */
    private int perTenantPerDay = 1000;
}
