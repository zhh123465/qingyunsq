package com.campusforum.social.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "social")
public class SocialLoginProperties {

    private final Qq qq = new Qq();
    private final Github github = new Github();
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 8000;
    /**
     * 出站代理（GitHub 等海外 API 在本服务器需经宿主机 7890 代理访问，
     * 与 note-sync.proxy 同机制）。为空则直连。
     */
    private String proxyHost;
    private int proxyPort;

    @Data
    public static class Qq {
        private String appId;
        private String appKey;
    }

    @Data
    public static class Github {
        private String clientId;
        private String clientSecret;
        /** OAuth App 的 Authorization callback URL，须与 GitHub 后台配置一致。 */
        private String redirectUri;
    }
}
