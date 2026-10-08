package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_messages")
public class AiMessage {
    @TableId
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private String model;
    private String knowledgeBaseIds;  // JSON array
    private String attachments;       // JSON array
    private String feedback;          // JSON object
    private LocalDateTime createdAt;
}
