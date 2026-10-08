package com.campusforum.announcement.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告返回体（前台 + 管理端共用）。
 */
@Data
@Builder
public class AnnouncementVO {
    private Long id;
    private String title;
    private String summary;
    /** Markdown 源码正文；列表页可省略以减包 */
    private String content;
    /** info / warning / critical */
    private String level;
    /** 0 / 1 */
    private Integer pinned;
    /** draft / published / archived（前台默认只返回 published） */
    private String status;
    private LocalDateTime publishTime;
    private LocalDateTime expireTime;
    private Long publisherId;
    private String publisherName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
