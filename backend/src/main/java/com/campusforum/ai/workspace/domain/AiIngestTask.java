package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_ingest_tasks")
public class AiIngestTask {
    @TableId
    private String taskId;
    private String knowledgeBaseId;
    private String status;
    private Integer progress;
    private String message;
}
