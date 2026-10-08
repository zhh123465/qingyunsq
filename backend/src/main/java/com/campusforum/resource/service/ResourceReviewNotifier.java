package com.campusforum.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.infra.email.EmailProperties;
import com.campusforum.infra.email.EmailService;
import com.campusforum.notify.service.NotifyService;
import com.campusforum.resource.domain.Resource;
import com.campusforum.resource.mapper.ResourceMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.util.List;

/**
 * 资源审核流通知器（2026-07-13）。
 *
 * <p>两个方向的通知：</p>
 * <ul>
 *   <li>{@link #onResourcePending}：用户上传落待审核后，站内通知 + 邮件提醒本租户全部
 *       SUPER_ADMIN。邮件经 Redis 节流（10 分钟窗口内最多一封，key 按租户隔离），
 *       避免连续上传轰炸管理员邮箱；邮件正文带当前待审总数与后台直达链接。</li>
 *   <li>{@link #onReviewResult}：审核通过/驳回后站内通知上传者（驳回附原因）。</li>
 * </ul>
 *
 * <p>调用方（ResourceService）已对本类所有入口做 try/catch，通知失败不影响主流程。
 * 邮箱地址过滤 *.local 占位后缀（微信/GitHub 第三方建号的假邮箱不可投递）。
 * 待审计数与收件人查询在调用方线程完成（依赖 TenantContext / MyBatis 租户插件），
 * 仅真正的 SMTP 发送放到 @Async 线程，避免跨线程丢租户上下文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReviewNotifier {

    private final UserMapper userMapper;
    private final ResourceMapper resourceMapper;
    private final NotifyService notifyService;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final StringRedisTemplate stringRedisTemplate;

    private static final Duration EMAIL_THROTTLE = Duration.ofMinutes(10);

    /** @Async 经 AOP 代理才生效，自调用必须走 selfProxy（与 ResourceService 同款范式）。 */
    private ResourceReviewNotifier selfProxy;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSelfProxy(@org.springframework.context.annotation.Lazy ResourceReviewNotifier selfProxy) {
        this.selfProxy = selfProxy;
    }

    /** 新资源进入待审核队列：站内通知全部超管，邮件节流提醒。 */
    public void onResourcePending(Resource resource) {
        List<User> admins = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "SUPER_ADMIN")
                .eq(User::getStatus, 1));
        if (admins.isEmpty()) {
            log.warn("Resource {} pending review but no SUPER_ADMIN found", resource.getId());
            return;
        }

        long pendingCount = resourceMapper.selectCount(new LambdaQueryWrapper<Resource>()
                .eq(Resource::getStatus, 2));

        for (User admin : admins) {
            notifyService.create(admin.getId(), resource.getUploaderId(), "RESOURCE_REVIEW",
                    "新资源待审核",
                    "「" + resource.getFileName() + "」等 " + pendingCount + " 个资源等待审核",
                    "/admin/resources");
        }

        // Redis 节流：10 分钟窗口内只发一封邮件（setIfAbsent 原子抢占）。
        // 租户插件只改写 INSERT SQL 不回填实体，tenantId 优先从 TenantContext 取。
        Long tenantId = com.campusforum.tenant.TenantContext.getTenantId();
        if (tenantId == null) tenantId = resource.getTenantId();
        String throttleKey = "resource_review_mail:tenant:" + (tenantId == null ? 0L : tenantId);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(throttleKey, "1", EMAIL_THROTTLE);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Resource review email throttled, pending={}", pendingCount);
            return;
        }

        List<String> emails = admins.stream()
                .map(User::getEmail)
                .filter(e -> e != null && e.contains("@") && !e.endsWith(".local"))
                .toList();
        if (emails.isEmpty()) {
            log.warn("Resource review email skipped: no deliverable SUPER_ADMIN email");
            return;
        }
        selfProxy.sendReviewMailAsync(emails, resource.getFileName(), pendingCount);
    }

    /** 审核结果通知上传者：approve → 已发布；reject → 附驳回原因。 */
    public void onReviewResult(Resource resource, boolean approved, String reason, Long reviewerId) {
        if (approved) {
            notifyService.create(resource.getUploaderId(), reviewerId, "RESOURCE_REVIEW",
                    "资源审核通过",
                    "你上传的「" + resource.getFileName() + "」已通过审核并发布",
                    "/resources/" + resource.getId());
        } else {
            notifyService.create(resource.getUploaderId(), reviewerId, "RESOURCE_REVIEW",
                    "资源审核未通过",
                    "你上传的「" + resource.getFileName() + "」被驳回：" + reason,
                    "/resources/" + resource.getId());
        }
    }

    /** 仅 SMTP 发送异步化；文件名做 HTML 转义防用户可控内容注入邮件正文。 */
    @Async
    public void sendReviewMailAsync(List<String> toEmails, String fileName, long pendingCount) {
        String subject = emailProperties.getAppName() + " - 有 " + pendingCount + " 个资源待审核";
        String adminUrl = emailProperties.getSiteBaseUrl() + "/admin/resources";
        String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background: #f8f9fa; border-radius: 8px; padding: 30px;">
                        <h2 style="color: #333; margin-top: 0;">%s 资源审核提醒</h2>
                        <p style="color: #666; line-height: 1.6;">
                            用户刚刚上传了资源「%s」，当前共有 <b>%d</b> 个资源等待审核。
                        </p>
                        <div style="text-align: center; margin: 30px 0;">
                            <a href="%s"
                               style="background: #4f46e5; color: white; padding: 12px 30px;
                                      text-decoration: none; border-radius: 6px; font-weight: bold;">
                                前往后台审核
                            </a>
                        </div>
                        <p style="color: #999; font-size: 14px; line-height: 1.5;">
                            审核提醒邮件 10 分钟内最多发送一封；期间新增的待审资源会累计在上述数字中。
                        </p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;">
                        <p style="color: #bbb; font-size: 12px;">
                            此邮件由 %s 系统自动发送，请勿回复。
                        </p>
                    </div>
                </body>
                </html>
                """.formatted(emailProperties.getAppName(),
                HtmlUtils.htmlEscape(fileName), pendingCount, adminUrl, emailProperties.getAppName());
        for (String to : toEmails) {
            try {
                emailService.sendNotice(to, subject, html);
            } catch (Exception e) {
                log.error("Resource review email failed to {}: {}", mask(to), e.getMessage());
            }
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 2 ? "***" + email.substring(Math.max(at, 0)) : email.substring(0, 2) + "***" + email.substring(at);
    }
}
