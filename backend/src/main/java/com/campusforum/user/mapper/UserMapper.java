package com.campusforum.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.user.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 绕过 @TableLogic 和租户插件，直接查询用户公开信息用于展示作者。 */
    @Select("SELECT nickname, avatar_url AS avatarUrl FROM users WHERE id = #{id}")
    Map<String, Object> selectPublicInfoById(@Param("id") Long id);

    @Select("<script>" +
            "SELECT id FROM users WHERE tenant_id = #{tenantId} " +
            "AND tag_subscriptions IS NOT NULL " +
            "AND tag_subscriptions != '' AND tag_subscriptions != '[]' " +
            "AND (" +
            "<foreach collection='tags' item='tag' separator=' OR '>" +
            "tag_subscriptions LIKE CONCAT('%\"', #{tag}, '\"%') ESCAPE '\\\\'" +
            "</foreach>" +
            ")" +
            "</script>")
    List<Long> selectUserIdsByTagSubscription(
            @Param("tenantId") Long tenantId,
            @Param("tags") List<String> tags);
}
