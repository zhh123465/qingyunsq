package com.campusforum.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.admin.dto.CategoryStat;
import com.campusforum.admin.dto.DailyStat;
import com.campusforum.admin.dto.DashboardVO;
import com.campusforum.common.R;
import com.campusforum.infra.audit.AuditLogService;
import com.campusforum.post.domain.Comment;
import com.campusforum.post.domain.Post;
import com.campusforum.post.mapper.CommentMapper;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.space.domain.Space;
import com.campusforum.space.mapper.SpaceMapper;
import com.campusforum.tenant.TenantContext;
import com.campusforum.tenant.cache.ActiveTenantCache;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final SpaceMapper spaceMapper;
    private final CommentMapper commentMapper;
    /** 用于把租户 code 注入 dashboard 响应（漏洞 14）。 */
    private final ActiveTenantCache activeTenantCache;
    private final AuditLogService auditLogService;

    @GetMapping("/dashboard")
    @SaCheckPermission("tenant:dashboard")
    public R<DashboardVO> dashboard() {
        LocalDate today = LocalDate.now();
        // 漏洞 14 修复：响应携带 tenantId / tenantCode，便于前端在面板顶部展示
        // "当前租户：xxx"，避免 SUPER_ADMIN 跨租户操作时误判数据归属
        Long tid = TenantContext.getTenantId();
        String tenantCode = tid != null ? activeTenantCache.getCode(tid) : null;
        return R.ok(DashboardVO.builder()
                .tenantId(tid)
                .tenantCode(tenantCode)
                .userCount(userMapper.selectCount(null))
                .postCount(postMapper.selectCount(null))
                .spaceCount(spaceMapper.selectCount(null))
                .commentCount(commentMapper.selectCount(null))
                .todayPostCount(postMapper.selectCount(
                        new LambdaQueryWrapper<Post>().ge(Post::getCreatedAt, today.atStartOfDay())))
                .todayUserCount(userMapper.selectCount(
                        new LambdaQueryWrapper<User>().ge(User::getCreatedAt, today.atStartOfDay())))
                .weeklyTrend(buildWeeklyTrend(today))
                .spaceCategoryDist(buildSpaceCategoryDist())
                .recentAuditLogs(auditLogService.page(null, 5, null, null))
                .build());
    }

    /** 过去 7 天（含今天）每天新增 posts/users/comments 的计数。缺失日期用 0 补齐。 */
    private List<DailyStat> buildWeeklyTrend(LocalDate today) {
        List<DailyStat> list = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            LocalDateTime start = d.atStartOfDay();
            LocalDateTime end = d.plusDays(1).atStartOfDay();
            Long np = postMapper.selectCount(new LambdaQueryWrapper<Post>()
                    .ge(Post::getCreatedAt, start).lt(Post::getCreatedAt, end));
            Long nu = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .ge(User::getCreatedAt, start).lt(User::getCreatedAt, end));
            Long nc = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                    .ge(Comment::getCreatedAt, start).lt(Comment::getCreatedAt, end));
            list.add(DailyStat.builder()
                    .date(d)
                    .newPosts(np == null ? 0L : np)
                    .newUsers(nu == null ? 0L : nu)
                    .newComments(nc == null ? 0L : nc)
                    .build());
        }
        return list;
    }

    /** 当前租户下 spaces 按 category 分组的分布，仅统计 status=1 的活跃空间。 */
    private List<CategoryStat> buildSpaceCategoryDist() {
        List<Space> spaces = spaceMapper.selectList(new LambdaQueryWrapper<Space>()
                .eq(Space::getStatus, 1));
        Map<String, Long> grouped = spaces.stream()
                .filter(s -> s.getCategory() != null && !s.getCategory().isBlank())
                .collect(Collectors.groupingBy(Space::getCategory, Collectors.counting()));
        return grouped.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> CategoryStat.builder().category(e.getKey()).count(e.getValue()).build())
                .toList();
    }
}
