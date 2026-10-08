package com.campusforum.post.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.post.domain.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PostMapper extends BaseMapper<Post> {

    @Update("UPDATE posts SET like_count = GREATEST(0, like_count + #{delta}) WHERE id = #{postId}")
    int incrementLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    @Update("UPDATE posts SET view_count = view_count + 1 WHERE id = #{postId}")
    int incrementViewCount(@Param("postId") Long postId);

    @Update("UPDATE posts SET comment_count = GREATEST(0, comment_count + #{delta}) WHERE id = #{postId}")
    int incrementCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    // MyBatis-Plus 逻辑删除会给 select 自动加 deleted=0，这里用原生 SQL 绕开，
    // 专供 admin 的"回收站"视图使用。多租户插件仍会自动追加 tenant_id 条件。
    @Select("SELECT * FROM posts WHERE deleted = 1 ${ew.customSqlSegment}")
    List<Post> selectTrash(@Param(Constants.WRAPPER) Wrapper<Post> ew);

    @Update("UPDATE posts SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    /** 物理删除。语义约束"仅允许对已在回收站（deleted=1）的记录彻底删除"，防止误删活跃记录。 */
    @Delete("DELETE FROM posts WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") Long id);

    // === 回收站分页版：走 MP PaginationInnerInterceptor 自动 LIMIT + COUNT(*) ===
    @Select("SELECT * FROM posts WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<Post> selectTrashPage(IPage<Post> page, @Param(Constants.WRAPPER) Wrapper<Post> ew);

    // === 批量操作（仅 admin 用，绕过 @TableLogic 的 select 拦截；写操作插件不拦截，Wrapper 判定 deleted 值即可）===
    @Update("<script>UPDATE posts SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE posts SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE posts SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
