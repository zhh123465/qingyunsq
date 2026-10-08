package com.campusforum.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.ai.domain.PostAiCard;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostAiCardMapper extends BaseMapper<PostAiCard> {

    /** 级联：物理删除某帖子对应的 AI 卡片。用于 Post.purge。 */
    @Delete("DELETE FROM post_ai_cards WHERE post_id = #{postId}")
    int physicalDeleteByPostId(@Param("postId") Long postId);
}
