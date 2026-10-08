package com.campusforum.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.post.domain.Reaction;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReactionMapper extends BaseMapper<Reaction> {

    /** 级联：物理删除某 target 下的全部反应。用于 Post/Comment/Resource.purge。 */
    @Delete("DELETE FROM reactions WHERE target_type = #{targetType} AND target_id = #{targetId}")
    int physicalDeleteByTarget(@Param("targetType") String targetType,
                               @Param("targetId") Long targetId);
}
