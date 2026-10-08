package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_knowledge_bases")
public class AiKnowledgeBase {
    @TableId
    private String id;
    private Long tenantId;
    private String name;
    private String description;
    private String category;
    private String type;
    private String visibility;
    private Long documentCount;
    private Long vectorCount;
    private Long storageBytes;
    private Long qaPairCount;
    private Long ownerId;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
