package com.campusforum.ai.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.ai.workspace.domain.AiKbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiKbChunkMapper extends BaseMapper<AiKbChunk> {

    @Delete("DELETE FROM ai_kb_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") String documentId);

    @Delete("DELETE FROM ai_kb_chunks WHERE knowledge_base_id = #{knowledgeBaseId}")
    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);
}
