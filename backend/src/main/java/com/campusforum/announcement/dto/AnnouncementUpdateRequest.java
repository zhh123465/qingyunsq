package com.campusforum.announcement.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编辑公告请求；字段皆可选，仅覆盖非 null 值。
 */
@Data
public class AnnouncementUpdateRequest {

    @Size(max = 200, message = "title 最长 200 字")
    private String title;

    @Size(max = 255, message = "summary 最长 255 字")
    private String summary;

    private String content;

    @Pattern(regexp = "^(info|warning|critical)$", message = "level 取值必须是 info/warning/critical 之一")
    private String level;

    private Integer pinned;

    @Pattern(regexp = "^(draft|published|archived)$", message = "status 取值必须是 draft/published/archived 之一")
    private String status;

    private LocalDateTime publishTime;

    private LocalDateTime expireTime;
}
