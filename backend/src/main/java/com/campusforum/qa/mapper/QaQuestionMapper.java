package com.campusforum.qa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.qa.domain.QaQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QaQuestionMapper extends BaseMapper<QaQuestion> {

    /** 级联：物理删除某帖子对应的问答扩展。用于 Post.purge。 */
    @Delete("DELETE FROM qa_questions WHERE post_id = #{postId}")
    int physicalDeleteByPostId(@Param("postId") Long postId);
}
