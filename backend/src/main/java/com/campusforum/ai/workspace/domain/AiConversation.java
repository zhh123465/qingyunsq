package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversations")
public class AiConversation {
    @TableId
    private String id;
    private Long tenantId;
    private String title;
    private String model;
    private String knowledgeBaseIds;  // JSON array
    private Long ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
