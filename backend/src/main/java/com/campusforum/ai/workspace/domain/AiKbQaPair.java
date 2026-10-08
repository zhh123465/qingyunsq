package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_kb_qa_pairs")
public class AiKbQaPair {
    @TableId
    private String id;
    private String knowledgeBaseId;
    private String question;
    private String answer;
    private String tags;         // JSON array
    private LocalDateTime createdAt;
}
