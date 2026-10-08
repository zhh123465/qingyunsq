package com.campusforum.infra.email;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "email")
public class EmailProperties {

    /** 发件人地址 */
    private String from = "noreply@campusforum.com";

    /** 密码重置链接基础 URL（前端页面地址） */
    private String resetLinkBase = "http://localhost:3000/reset-password";

    /** 站点基础 URL（用于拼接后台直达链接等），末尾不带斜杠 */
    private String siteBaseUrl = "http://localhost:3000";

    /** 应用名称（显示在邮件中） */
    private String appName = "CampusForum";

    /** 邮箱验证码过期时间（分钟） */
    private int codeExpireMinutes = 10;

    /** 重置令牌过期时间（分钟） */
    private int resetTokenExpireMinutes = 30;

    /** 频率限制：每个邮箱在限制窗口内最多请求次数 */
    private int rateLimitMaxRequests = 5;

    /** 频率限制：窗口时间（分钟） */
    private int rateLimitWindowMinutes = 15;
}
