package com.campusforum.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.common.R;
import com.campusforum.infra.email.EmailCodeScene;
import com.campusforum.infra.security.WsTicketService;
import com.campusforum.user.dto.ChangePasswordRequest;
import com.campusforum.user.dto.EmailCodeRequest;
import com.campusforum.user.dto.EmailOnlyRequest;
import com.campusforum.user.dto.LoginRequest;
import com.campusforum.user.dto.RegisterRequest;
import com.campusforum.user.dto.ResetPasswordRequest;
import com.campusforum.user.dto.UserVO;
import com.campusforum.user.dto.WechatLoginRequest;
import com.campusforum.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final WsTicketService wsTicketService;
    private final com.campusforum.social.service.GithubLoginService githubLoginService;

    @PostMapping("/register")
    public R<UserVO> register(@Valid @RequestBody RegisterRequest req) {
        UserVO user = userService.register(req);
        return R.ok(user);
    }

    @PostMapping("/email-code")
    public R<Map<String, String>> sendEmailCode(@Valid @RequestBody EmailCodeRequest req) {
        userService.sendEmailCode(req.getEmail(), parseScene(req.getScene()));
        return R.ok(Map.of("message", "验证码已发送，请查收邮箱"));
    }

    @PostMapping("/email-exists")
    public R<Map<String, Boolean>> emailExists(@Valid @RequestBody EmailOnlyRequest req) {
        boolean exists = userService.existsByEmail(req.getEmail());
        return R.ok(Map.of("exists", exists));
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        UserVO user = userService.login(req);
        String token = StpUtil.getTokenValue();
        return R.ok(Map.of(
                "token", token,
                "user", user
        ));
    }

    @PostMapping("/wechat-login")
    public R<Map<String, Object>> wechatLogin(@Valid @RequestBody WechatLoginRequest req) {
        UserVO user = userService.loginByWechatMiniProgramCode(req.getCode());
        String token = StpUtil.getTokenValue();
        return R.ok(Map.of(
                "token", token,
                "tenantId", user.getTenantId(),
                "tenantCode", user.getTenantCode(),
                "user", user
        ));
    }

    /**
     * GitHub 登录第一步：返回授权页 URL（含防 CSRF 的 state，Redis 10 分钟 TTL）。
     * 前端 window.location 跳转该 URL，GitHub 授权后 302 回 redirect_uri?code=..&state=..
     */
    @GetMapping("/github/authorize-url")
    public R<Map<String, Object>> githubAuthorizeUrl() {
        if (!githubLoginService.isConfigured()) {
            return R.ok(Map.of("enabled", false));
        }
        return R.ok(Map.of("enabled", true, "url", githubLoginService.createAuthorizeUrl()));
    }

    /** GitHub 登录第二步：校验 state + 授权码换 token + 建号/登录。 */
    @PostMapping("/github-login")
    public R<Map<String, Object>> githubLogin(@Valid @RequestBody com.campusforum.user.dto.GithubLoginRequest req) {
        UserVO user = githubLoginService.login(req.getCode(), req.getState());
        String token = StpUtil.getTokenValue();
        return R.ok(Map.of(
                "token", token,
                "user", user
        ));
    }

    @PostMapping("/logout")
    public R<?> logout() {
        userService.logout();
        return R.ok();
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        long userId = StpUtil.getLoginIdAsLong();
        UserVO user = userService.getById(userId);
        return R.ok(user);
    }

    @PutMapping("/password")
    public R<?> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        long userId = StpUtil.getLoginIdAsLong();
        userService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }

    @PostMapping("/forgot-password")
    public R<Map<String, String>> forgotPassword(@Valid @RequestBody EmailOnlyRequest req) {
        userService.forgotPassword(req.getEmail());
        return R.ok(Map.of("message", "如该邮箱已注册，验证码将发送至您的邮箱"));
    }

    @PostMapping("/reset-password")
    public R<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(req.getEmail(), req.getEmailCode(), req.getNewPassword());
        return R.ok();
    }

    /**
     * 颁发 WebSocket 一次性票据（缺陷 1.3 加固）。
     *
     * <p>客户端在建立 WebSocket 连接前调用本接口拿到短期票据，
     * 用 {@code ?ticket=xxx} 替代将 Sa-Token 主令牌写入 URL，
     * 避免主令牌泄漏到 nginx access log / 浏览器历史 / Referer 头。</p>
     */
    @PostMapping("/ws-ticket")
    public R<Map<String, Object>> wsTicket() {
        long userId = StpUtil.getLoginIdAsLong();
        Object tid = StpUtil.getSession().get("tenantId");
        if (!(tid instanceof Number)) {
            // session 中 tenantId 缺失视为异常，要求重新登录
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        WsTicketService.Ticket t = wsTicketService.issue(userId, ((Number) tid).longValue());
        return R.ok(Map.of(
                "ticket", t.token(),
                "expiresAt", t.expiresAtSeconds()
        ));
    }

    private EmailCodeScene parseScene(String scene) {
        try {
            return EmailCodeScene.valueOf(scene.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "验证码用途不正确");
        }
    }
}
