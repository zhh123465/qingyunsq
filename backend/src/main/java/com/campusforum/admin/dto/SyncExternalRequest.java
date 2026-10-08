package com.campusforum.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 外部笔记同步请求 DTO。
 *
 * <p>三种同步模式（{@code source}）：</p>
 * <ul>
 *   <li>{@code single}：单页；直接抓 {@code rootUrl} 存 1 条笔记。</li>
 *   <li>{@code vitepress}：VitePress 站点；抓 {@code rootUrl} 目录页，遍历所有 {@code .html}
 *       子页；{@code recursive=true} 时深入子目录。</li>
 *   <li>{@code cnblogs}：博客园用户主页；遍历分页 {@code /default.html?page=N}
 *       抓取用户所有文章。</li>
 * </ul>
 */
@Data
public class SyncExternalRequest {

    /** 同步模式，仅允许 vitepress / cnblogs / single。 */
    @NotBlank
    @Pattern(regexp = "^(vitepress|cnblogs|single)$", message = "source 只允许 vitepress/cnblogs/single")
    private String source;

    /** 起始 URL：必须 http(s) 前缀。 */
    @NotBlank
    @Size(max = 512)
    @Pattern(regexp = "^https?://.+", message = "rootUrl 必须是 http(s) URL")
    private String rootUrl;

    /** 仅对 vitepress 生效：是否递归子目录。默认 false（只处理一层）。 */
    private Boolean recursive = false;

    /** 原站显示名（如 "cnblogs.com/LFmin"）。 */
    @NotBlank
    @Size(max = 128)
    private String sourceName;

    /** 原作者显示名。 */
    @NotBlank
    @Size(max = 128)
    private String sourceAuthor;

    /** 打给同步进来的每条笔记的 tags（最多 8 个，每个 ≤ 32 字符）。 */
    @Size(max = 8)
    private List<@NotBlank @Size(max = 32) String> tags;

    /** 这批笔记归属的 owner_id（通常填 1 = SUPER_ADMIN，让"作者"字段显示超管）。 */
    @NotNull
    @Min(1)
    private Long ownerId;
}
