package com.campusforum.announcement.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campusforum.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("announcements")
public class Announcement extends BaseEntity {

    /** 公告标题（≤200 chars） */
    private String title;

    /** 横幅短摘要（≤255 chars），NULL 时前端截 content 前 80 字 */
    private String summary;

    /** Markdown 源码正文 */
    private String content;

    /** 级别：info / warning / critical */
    private String level;

    /** 是否置顶（0/1） */
    private Integer pinned;

    /** 发布人 userId */
    private Long publisherId;

    /** 状态：draft / published / archived */
    private String status;

    /** 生效时间（NULL=立即） */
    private LocalDateTime publishTime;

    /** 过期时间（NULL=永不） */
    private LocalDateTime expireTime;
}
