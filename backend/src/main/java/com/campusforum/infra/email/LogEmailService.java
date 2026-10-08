package com.campusforum.infra.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 开发环境专用的「假」邮件实现（仅 dev profile 生效）。
 *
 * <p>
 * 背景：本地开发机开启了代理（fake-ip / TUN 模式），外发到 smtp.qq.com:465 的 SMTP 流量在 TLS
 * 握手阶段被代理中断，导致真实邮件无法发送。为了让注册、 登录、找回密码等依赖验证码的流程在本地可调通，这里不连接任何 SMTP 服务器， 直接把验证码 /
 * 重置令牌打印到控制台日志，从日志即可读取。</p>
 *
 * <p>
 * 生产环境（非 dev profile）仍由 {@link SmtpEmailService} 发送真实邮件， 两者通过 {@code @Profile}
 * 互斥，保证容器中只有一个 {@link EmailService} 实例。</p>
 *
 * <p>
 * <b>安全提示：</b>该实现会把验证码明文打到日志，仅可用于本地开发， 切勿在任何对外环境启用 dev profile。</p>
 */
@Slf4j
@Service
@Profile("dev")
@ConditionalOnProperty(prefix = "email", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class LogEmailService implements EmailService {

    @Override
    public void sendVerificationCode(String toEmail, EmailCodeScene scene, String code, int expireMinutes) {
        log.warn("====== [DEV-MOCK 邮件] 验证码（未真实发送）======");
        log.warn("  收件人 : {}", toEmail);
        log.warn("  场景   : {} ({})", scene.name(), scene.getSubjectLabel());
        log.warn("  验证码 : {}", code);
        log.warn("  有效期 : {} 分钟", expireMinutes);
        log.warn("===============================================");
    }

    @Override
    public void sendResetEmail(String toEmail, String resetToken) {
        log.warn("====== [DEV-MOCK 邮件] 密码重置（未真实发送）======");
        log.warn("  收件人   : {}", toEmail);
        log.warn("  重置令牌 : {}", resetToken);
        log.warn("=================================================");
    }

    @Override
    public void sendNotice(String toEmail, String subject, String htmlContent) {
        log.warn("====== [DEV-MOCK 邮件] 通知（未真实发送）======");
        log.warn("  收件人 : {}", toEmail);
        log.warn("  主题   : {}", subject);
        log.warn("===============================================");
    }
}
