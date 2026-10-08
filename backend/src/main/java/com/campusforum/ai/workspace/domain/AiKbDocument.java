package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_kb_documents")
public class AiKbDocument {
    @TableId
    private String id;
    private String knowledgeBaseId;
    private String fileName;
    private Long fileSize;
    private String storageKey;
    private String tags;         // JSON array
    private String parseMode;
    private String status;
    private LocalDateTime createdAt;
}
