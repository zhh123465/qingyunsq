package com.campusforum.social.service;

import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.user.dto.UserVO;
import com.campusforum.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * GitHub 授权码登录编排（2026-07-12 新增）。
 *
 * <p>state 防 CSRF：{@link #createAuthorizeUrl} 生成随机 state 写入 Redis
 * （10 分钟 TTL），回调侧 {@link #login} 校验并一次性消费——防止攻击者
 * 用自己的授权码钓鱼绑定到受害者会话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubLoginService {

    private static final String STATE_KEY_PREFIX = "github_oauth_state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final GithubOAuthClient githubOAuthClient;
    private final UserService userService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 生成授权页 URL（含防 CSRF 的 state）。 */
    public String createAuthorizeUrl() {
        byte[] buf = new byte[16];
        secureRandom.nextBytes(buf);
        String state = HexFormat.of().formatHex(buf);
        stringRedisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, "1", STATE_TTL);
        return githubOAuthClient.buildAuthorizeUrl(state);
    }

    /** 校验 state → code 换 token → 取用户 → 建号/登录。 */
    public UserVO login(String code, String state) {
        if (!StringUtils.hasText(state)
                || !Boolean.TRUE.equals(stringRedisTemplate.delete(STATE_KEY_PREFIX + state))) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getCode(),
                    "GitHub 登录状态无效或已过期，请重新发起登录");
        }
        String accessToken = githubOAuthClient.exchangeCode(code);
        GithubUserInfo info = githubOAuthClient.getUserInfo(accessToken);
        return userService.loginByGithub(info);
    }

    public boolean isConfigured() {
        return githubOAuthClient.isConfigured();
    }
}
