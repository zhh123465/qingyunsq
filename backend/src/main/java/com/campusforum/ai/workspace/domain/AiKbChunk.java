package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档切块（真实 RAG 的检索单元，2026-07-12 新增）。
 * 文档上传时经文本抽取（Tika/POI/PDFBox/jsoup）后按段落切块入库，
 * 对话挂载知识库时按关键词相关度检索 top-N 切块注入 prompt。
 */
@Data
@TableName("ai_kb_chunks")
public class AiKbChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String knowledgeBaseId;
    private String documentId;
    private Integer chunkIndex;
    private String content;
    private LocalDateTime createdAt;
}
