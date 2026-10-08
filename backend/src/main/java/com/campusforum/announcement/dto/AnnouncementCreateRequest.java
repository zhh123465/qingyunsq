package com.campusforum.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnnouncementCreateRequest {

    @NotBlank(message = "title 不能为空")
    @Size(max = 200, message = "title 最长 200 字")
    private String title;

    @Size(max = 255, message = "summary 最长 255 字")
    private String summary;

    @NotBlank(message = "content 不能为空")
    private String content;

    /** info / warning / critical，默认 info */
    @Pattern(regexp = "^(info|warning|critical)$", message = "level 取值必须是 info/warning/critical 之一")
    private String level;

    /** 0/1，默认 0 */
    private Integer pinned;

    /**
     * 期望初始状态：draft / published / archived。
     * 常见场景：管理端提交时选"保存草稿" → draft；点"发布" → published。
     */
    @Pattern(regexp = "^(draft|published|archived)$", message = "status 取值必须是 draft/published/archived 之一")
    private String status;

    private LocalDateTime publishTime;

    private LocalDateTime expireTime;
}
