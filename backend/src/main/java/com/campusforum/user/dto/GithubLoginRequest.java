package com.campusforum.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** GitHub 授权码登录请求（code/state 均来自 GitHub 回调 query）。 */
@Data
public class GithubLoginRequest {

    @NotBlank(message = "授权码不能为空")
    @Size(max = 128)
    private String code;

    @NotBlank(message = "state 不能为空")
    @Size(max = 64)
    private String state;
}
