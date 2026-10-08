package com.campusforum.ai.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.ai.workspace.domain.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
    @Select("SELECT * FROM ai_messages WHERE conversation_id = #{conversationId} ORDER BY created_at ASC")
    List<AiMessage> findByConversationId(String conversationId);
}
