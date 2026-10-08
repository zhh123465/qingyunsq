package com.campusforum.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // 1xxxx 业务错误
    USER_NOT_FOUND(10001, "用户不存在"),       // 仅保留给已认证管理员操作使用，登录路径请使用 INVALID_CREDENTIALS
    WRONG_PASSWORD(10002, "密码错误"),         // 仅保留给已认证管理员操作使用，登录路径请使用 INVALID_CREDENTIALS
    USER_BANNED(10003, "账号已被封禁"),        // 仅保留给已认证管理员操作使用，登录路径请使用 INVALID_CREDENTIALS
    SPACE_NOT_FOUND(10101, "空间不存在"),
    SPACE_FULL(10102, "空间成员已满"),
    POST_NOT_FOUND(10201, "帖子不存在"),
    COMMENT_NOT_FOUND(10301, "评论不存在"),
    CHALLENGE_NOT_FOUND(10501, "打卡挑战不存在"),
    ALREADY_CHECKED_IN(10502, "今日已打卡"),
    RESOURCE_NOT_FOUND(10601, "资源不存在"),
    QUESTION_ALREADY_SOLVED(10701, "问题已有采纳答案"),
    ANNOUNCEMENT_NOT_FOUND(10801, "公告不存在"),

    // 4xxxx 客户端错误
    BAD_REQUEST(40000, "请求参数错误"),
    SSRF_BLOCKED(40005, "禁止指向内网或本机地址"),
    MIME_MISMATCH(40006, "文件类型与扩展名不一致"),
    RESET_TOKEN_INVALID(40007, "重置令牌无效或已过期"),
    BATCH_SIZE_EXCEEDED(40008, "单次最多处理 100 条"),
    TENANT_NOT_RESOLVED(40010, "无法识别租户"),
    TENANT_NOT_FOUND(40011, "租户不存在或已停用"),
    // === 安全加固扩展（任务 T9.5 / 漏洞 12、13、25） ===
    /** 租户上下文不一致：例如 session 解析的 tenantId 与子域名解析不符。 */
    TENANT_MISMATCH(40012, "租户上下文不一致"),
    /** 接口文档不可访问：DocAccessFilter 拒绝外部访问 swagger / api-docs。 */
    DOC_ACCESS_DENIED(40013, "接口文档不可访问"),
    /** 无导出权限：ExportController 对 fullPii 等敏感导出做二次校验。 */
    EXPORT_FORBIDDEN(40014, "无导出权限"),
    UNAUTHORIZED(40100, "未登录"),
    INVALID_CREDENTIALS(40101, "邮箱或密码错误"),
    FORBIDDEN(40300, "无权限"),
    NOT_FOUND(40400, "资源不存在"),
    RATE_LIMITED(42900, "请求过于频繁"),

    // 5xxxx 服务端错误
    INTERNAL_ERROR(50000, "服务器内部错误"),
    AI_SERVICE_UNAVAILABLE(50001, "AI 服务暂不可用"),
    SEARCH_UNAVAILABLE(50002, "搜索服务暂不可用"),
    STORAGE_ERROR(50003, "文件存储异常"),
    CRYPTO_FAILURE(50010, "加密服务异常"),
    /** 服务器配置不安全：SecurityStartupValidator 在 prod profile 命中弱默认值时使用。 */
    WEAK_CONFIG(50011, "服务器配置不安全，请联系运维"),
    SERVICE_UNAVAILABLE(50301, "服务暂时不可用，请稍后重试"),
    TENANT_VIOLATION(51001, "租户数据隔离异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
