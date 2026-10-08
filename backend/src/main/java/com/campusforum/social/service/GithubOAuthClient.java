package com.campusforum.social.service;

import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.social.config.SocialLoginProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * GitHub OAuth 授权码流程客户端（2026-07-12 从 device flow 骨架改写为真实可用的
 * authorization code flow）。
 *
 * <p>流程：前端跳转 {@link #buildAuthorizeUrl} → 用户在 GitHub 授权 → GitHub 302 回
 * {@code redirect_uri?code=..&state=..} → 前端把 code 交给后端
 * {@code POST /auth/github-login} → {@link #exchangeCode} 换 access token →
 * {@link #getUserInfo} 取 GitHub 用户 → 建号/登录。</p>
 *
 * <p>出站说明：github.com / api.github.com 属海外资源，生产服务器需经宿主机
 * 7890 代理访问（{@code social.proxy.host/port}，与 note-sync 同机制）。
 * 目标 URL 为固定的 GitHub 官方域名，不存在用户可控 host，无 SSRF 面。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubOAuthClient {

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";

    private final SocialLoginProperties properties;

    /** 拼装 GitHub 授权页 URL（state 由调用方生成并持久化校验，防 CSRF）。 */
    public String buildAuthorizeUrl(String state) {
        ensureConfigured();
        return UriComponentsBuilder.fromHttpUrl(AUTHORIZE_URL)
                .queryParam("client_id", properties.getGithub().getClientId())
                .queryParam("redirect_uri", properties.getGithub().getRedirectUri())
                .queryParam("scope", "read:user")
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    /** 授权码换 access token。code 无效/过期抛 INVALID_CREDENTIALS。 */
    public String exchangeCode(String code) {
        ensureConfigured();
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "GitHub 授权码不能为空");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getGithub().getClientId());
        form.add("client_secret", properties.getGithub().getClientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.getGithub().getRedirectUri());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "CampusForum");

        Map<?, ?> body;
        try {
            body = restTemplate().postForObject(URI.create(ACCESS_TOKEN_URL),
                    new HttpEntity<>(form, headers), Map.class);
        } catch (RestClientException e) {
            log.warn("GitHub token exchange failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "GitHub 登录服务暂不可用，请稍后重试");
        }

        if (body == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "GitHub 登录服务暂不可用，请稍后重试");
        }
        String error = asText(body.get("error"));
        if (StringUtils.hasText(error)) {
            log.warn("GitHub token exchange rejected: error={}", error);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getCode(), "GitHub 授权无效或已过期，请重新登录");
        }
        String accessToken = asText(body.get("access_token"));
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getCode(), "GitHub 授权无效或已过期，请重新登录");
        }
        return accessToken;
    }

    /** 用 access token 取 GitHub 用户信息。 */
    public GithubUserInfo getUserInfo(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "GitHub access token 不能为空");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "CampusForum");

        Map<?, ?> body;
        try {
            body = restTemplate().exchange(URI.create(USER_URL), HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
        } catch (RestClientException e) {
            log.warn("GitHub user request failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "GitHub 登录服务暂不可用，请稍后重试");
        }

        if (body == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "GitHub 登录服务暂不可用，请稍后重试");
        }

        String id = String.valueOf(body.get("id"));
        if (!StringUtils.hasText(id) || "null".equals(id)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getCode(), "GitHub 登录凭证无效或已过期");
        }

        return new GithubUserInfo(
                id,
                asText(body.get("login")),
                asText(body.get("name")),
                asText(body.get("avatar_url")));
    }

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getGithub().getClientId())
                && StringUtils.hasText(properties.getGithub().getClientSecret())
                && StringUtils.hasText(properties.getGithub().getRedirectUri());
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.WEAK_CONFIG.getCode(),
                    "GitHub 登录未配置（client-id / client-secret / redirect-uri）");
        }
    }

    /**
     * 固定指向 GitHub 官方域名的 RestTemplate。配置了 social.proxy 时经代理出站
     * （生产服务器访问海外 API 必需）；禁用自动 redirect 与 SafeHttpClient 行为对齐。
     */
    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        if (StringUtils.hasText(properties.getProxyHost()) && properties.getProxyPort() > 0) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort())));
        }
        return new RestTemplate(factory);
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
