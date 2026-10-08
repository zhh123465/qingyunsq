package com.campusforum.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理面板首页统计 VO。
 *
 * <p>对应 bugfix.md 漏洞 14（dashboard 范围识别）：早期 VO 只包含计数字段，
 * 前端无法识别"我看到的是哪个租户的数据"，让 SUPER_ADMIN 跨租户操作时
 * 难以察觉自己已经切到了别的租户上下文。T6.5 加固：始终在响应中带上当前
 * 租户 ID 与 code，便于前端在面板顶部显示"当前租户：xxx"。</p>
 */
@Data
@Builder
public class DashboardVO {
    /** 当前响应所属的租户 ID（来自 TenantContext，权威来源）。 */
    private Long tenantId;
    /** 当前租户 code（来自 ActiveTenantCache，便于前端展示）。 */
    private String tenantCode;
    private Long userCount;
    private Long postCount;
    private Long spaceCount;
    private Long commentCount;
    private Long todayPostCount;
    private Long todayUserCount;

    // === 扩展字段：dashboard 换真数据 ===
    /** 过去 7 天（含今天）每天新增帖子/用户/评论数量，按日期升序，缺失日期补 0。 */
    private List<DailyStat> weeklyTrend;
    /** 当前租户下 spaces 按 category 分组的分布，用于饼图展示。 */
    private List<CategoryStat> spaceCategoryDist;
    /** 最近 5 条审计日志，用于面板底部"最近操作"卡片。 */
    private List<AuditLogVO> recentAuditLogs;
}
