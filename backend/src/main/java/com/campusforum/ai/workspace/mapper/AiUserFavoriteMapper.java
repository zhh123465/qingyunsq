package com.campusforum.ai.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.ai.workspace.domain.AiUserFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiUserFavoriteMapper extends BaseMapper<AiUserFavorite> {
    @Select("SELECT target_id FROM ai_user_favorites WHERE user_id = #{userId} AND favorite_type = #{type}")
    List<String> findTargetIds(@Param("userId") Long userId, @Param("type") String type);

    @Delete("DELETE FROM ai_user_favorites WHERE user_id = #{userId} AND favorite_type = #{type} AND target_id = #{targetId}")
    int removeFavorite(@Param("userId") Long userId, @Param("type") String type, @Param("targetId") String targetId);
}
