package com.campusforum.wechat.service;

import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.security.SafeHttpClient;
import com.campusforum.wechat.config.WechatMiniProgramProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WechatMiniProgramClient {

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final WechatMiniProgramProperties properties;
    private final ObjectMapper objectMapper;

    public WechatCodeSession code2Session(String code) {
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            throw new BusinessException(ErrorCode.WEAK_CONFIG.getCode(), "微信登录未配置 AppID 或 AppSecret");
        }

        URI uri = UriComponentsBuilder.fromHttpUrl(CODE2SESSION_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build(true)
                .toUri();

        Map<?, ?> body;
        try {
            RestTemplate restTemplate = SafeHttpClient.build(
                    properties.getConnectTimeoutMs(),
                    properties.getReadTimeoutMs());
            String responseBody = restTemplate.getForObject(uri, String.class);
            body = parseResponse(responseBody);
        } catch (RestClientException e) {
            log.warn("Wechat code2Session request failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "微信登录服务暂不可用，请稍后重试");
        }

        if (body == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "微信登录服务暂不可用，请稍后重试");
        }

        Object errcode = body.get("errcode");
        if (errcode instanceof Number number && number.intValue() != 0) {
            log.warn("Wechat code2Session rejected code, errcode={}, errmsg={}",
                    number.intValue(), body.get("errmsg"));
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getCode(), "微信登录凭证无效或已过期");
        }

        String openid = asString(body.get("openid"));
        if (!StringUtils.hasText(openid)) {
            log.warn("Wechat code2Session response missing openid: keys={}", body.keySet());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "微信登录服务暂不可用，请稍后重试");
        }

        return new WechatCodeSession(
                openid,
                asString(body.get("unionid")),
                asString(body.get("session_key")));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<?, ?> parseResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            String summary = responseBody.length() > 120 ? responseBody.substring(0, 120) : responseBody;
            log.warn("Wechat code2Session returned non-json response: {}", summary);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "微信登录服务暂不可用，请稍后重试");
        }
    }
}
