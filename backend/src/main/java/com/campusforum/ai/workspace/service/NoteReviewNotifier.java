package com.campusforum.ai.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.ai.workspace.domain.AiNote;
import com.campusforum.ai.workspace.mapper.AiNoteMapper;
import com.campusforum.infra.email.EmailProperties;
import com.campusforum.infra.email.EmailService;
import com.campusforum.notify.service.NotifyService;
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
 * 笔记审核流通知器（2026-07-16），照 {@link com.campusforum.resource.service.ResourceReviewNotifier}
 * 同款范式（该模式已在生产全链路验证）。
 *
 * <p>两个方向的通知：</p>
 * <ul>
 *   <li>{@link #onNotePending}：用户提交发布落待审核（pending）后，站内通知 + 邮件提醒本租户全部
 *       SUPER_ADMIN。邮件经 Redis 节流（10 分钟窗口内最多一封，key 按租户隔离、与资源审核的
 *       throttle key 相互独立），避免连续提交轰炸管理员邮箱。</li>
 *   <li>{@link #onReviewResult}：审核通过/驳回后站内通知作者（驳回附原因）。</li>
 * </ul>
 *
 * <p>调用方（AiWorkspaceService）已对本类所有入口做 try/catch，通知失败不影响主流程。
 * 邮箱地址过滤 *.local 占位后缀（微信/GitHub 第三方建号的假邮箱不可投递）。
 * 待审计数与收件人查询在调用方线程完成（依赖 TenantContext / MyBatis 租户插件），
 * 仅真正的 SMTP 发送放到 @Async 线程，避免跨线程丢租户上下文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteReviewNotifier {

    private final UserMapper userMapper;
    private final AiNoteMapper noteMapper;
    private final NotifyService notifyService;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final StringRedisTemplate stringRedisTemplate;

    private static final Duration EMAIL_THROTTLE = Duration.ofMinutes(10);

    /** @Async 经 AOP 代理才生效，自调用必须走 selfProxy（与 ResourceReviewNotifier 同款范式）。 */
    private NoteReviewNotifier selfProxy;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSelfProxy(@org.springframework.context.annotation.Lazy NoteReviewNotifier selfProxy) {
        this.selfProxy = selfProxy;
    }

    /** 新笔记进入待审核队列：站内通知全部超管，邮件节流提醒。 */
    public void onNotePending(AiNote note) {
        List<User> admins = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "SUPER_ADMIN")
                .eq(User::getStatus, 1));
        if (admins.isEmpty()) {
            log.warn("Note {} pending review but no SUPER_ADMIN found", note.getId());
            return;
        }

        long pendingCount = noteMapper.selectCount(new LambdaQueryWrapper<AiNote>()
                .eq(AiNote::getStatus, "pending"));

        for (User admin : admins) {
            notifyService.create(admin.getId(), note.getOwnerId(), "NOTE_REVIEW",
                    "新笔记待审核",
                    "「" + note.getTitle() + "」等 " + pendingCount + " 篇笔记等待审核",
                    "/admin/notes");
        }

        // Redis 节流：10 分钟窗口内只发一封邮件（setIfAbsent 原子抢占）。
        // 租户插件只改写 SQL 不回填实体，tenantId 优先从 TenantContext 取。
        Long tenantId = com.campusforum.tenant.TenantContext.getTenantId();
        if (tenantId == null) tenantId = note.getTenantId();
        String throttleKey = "note_review_mail:tenant:" + (tenantId == null ? 0L : tenantId);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(throttleKey, "1", EMAIL_THROTTLE);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Note review email throttled, pending={}", pendingCount);
            return;
        }

        List<String> emails = admins.stream()
                .map(User::getEmail)
                .filter(e -> e != null && e.contains("@") && !e.endsWith(".local"))
                .toList();
        if (emails.isEmpty()) {
            log.warn("Note review email skipped: no deliverable SUPER_ADMIN email");
            return;
        }
        selfProxy.sendReviewMailAsync(emails, note.getTitle(), pendingCount);
    }

    /** 审核结果通知作者：approve → 已公开；reject → 附驳回原因（引导回编辑器修改）。 */
    public void onReviewResult(AiNote note, boolean approved, String reason, Long reviewerId) {
        if (approved) {
            notifyService.create(note.getOwnerId(), reviewerId, "NOTE_REVIEW",
                    "笔记审核通过",
                    "你的笔记「" + note.getTitle() + "」已通过审核并公开发布",
                    "/learning/notes/" + note.getId());
        } else {
            notifyService.create(note.getOwnerId(), reviewerId, "NOTE_REVIEW",
                    "笔记审核未通过",
                    "你的笔记「" + note.getTitle() + "」被驳回：" + reason,
                    "/ai/notes");
        }
    }

    /** 仅 SMTP 发送异步化；标题做 HTML 转义防用户可控内容注入邮件正文。 */
    @Async
    public void sendReviewMailAsync(List<String> toEmails, String noteTitle, long pendingCount) {
        String subject = emailProperties.getAppName() + " - 有 " + pendingCount + " 篇笔记待审核";
        String adminUrl = emailProperties.getSiteBaseUrl() + "/admin/notes";
        String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background: #f8f9fa; border-radius: 8px; padding: 30px;">
                        <h2 style="color: #333; margin-top: 0;">%s 笔记审核提醒</h2>
                        <p style="color: #666; line-height: 1.6;">
                            用户刚刚提交了笔记「%s」，当前共有 <b>%d</b> 篇笔记等待审核。
                        </p>
                        <div style="text-align: center; margin: 30px 0;">
                            <a href="%s"
                               style="background: #4f46e5; color: white; padding: 12px 30px;
                                      text-decoration: none; border-radius: 6px; font-weight: bold;">
                                前往后台审核
                            </a>
                        </div>
                        <p style="color: #999; font-size: 14px; line-height: 1.5;">
                            审核提醒邮件 10 分钟内最多发送一封；期间新增的待审笔记会累计在上述数字中。
                        </p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;">
                        <p style="color: #bbb; font-size: 12px;">
                            此邮件由 %s 系统自动发送，请勿回复。
                        </p>
                    </div>
                </body>
                </html>
                """.formatted(emailProperties.getAppName(),
                HtmlUtils.htmlEscape(noteTitle), pendingCount, adminUrl, emailProperties.getAppName());
        for (String to : toEmails) {
            try {
                emailService.sendNotice(to, subject, html);
            } catch (Exception e) {
                log.error("Note review email failed to {}: {}", mask(to), e.getMessage());
            }
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 2 ? "***" + email.substring(Math.max(at, 0)) : email.substring(0, 2) + "***" + email.substring(at);
    }
}
