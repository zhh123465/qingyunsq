package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_notes")
public class AiNote {
    @TableId
    private String id;
    private Long tenantId;
    private String title;
    private String content;
    private String contentOssKey;
    private String contentType;
    private String tags;
    /** draft=草稿 pending=待审核 published=已发布 rejected=已驳回 hidden=管理员隐藏 */
    private String status;
    /** 驳回原因（status=rejected 时有值）。 */
    private String reviewReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private Long viewCount;
    private Long ownerId;
    private String knowledgeBaseId;
    /** 外部原文 URL：非空表示同步的友链笔记（学习页卡片展示 ↗ + 详情页显示出处 banner）。 */
    private String sourceUrl;
    /** 原站显示名（如 "cnblogs.com/LFmin"）。 */
    private String sourceName;
    /** 原作者显示名（如 "LFmin"）。 */
    private String sourceAuthor;
    /** 展示权重（数值大靠前，管理员在学习页"编辑排序"时批量写入）。 */
    private Integer sortOrder;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
