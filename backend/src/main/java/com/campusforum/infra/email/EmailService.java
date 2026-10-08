package com.campusforum.infra.email;

/**
 * 邮件发送服务接口
 */
public interface EmailService {

    /**
     * 发送邮箱验证码。
     *
     * @param toEmail 收件人邮箱
     * @param scene 验证码用途
     * @param code 验证码明文
     * @param expireMinutes 过期分钟数
     */
    void sendVerificationCode(String toEmail, EmailCodeScene scene, String code, int expireMinutes);

    /**
     * 发送密码重置邮件
     *
     * @param toEmail 收件人邮箱
     * @param resetToken 重置令牌
     */
    void sendResetEmail(String toEmail, String resetToken);

    /**
     * 发送通用通知邮件（如资源审核提醒）。
     *
     * @param toEmail 收件人邮箱
     * @param subject 邮件主题
     * @param htmlContent HTML 正文（调用方负责对用户可控内容做转义）
     */
    void sendNotice(String toEmail, String subject, String htmlContent);
}
