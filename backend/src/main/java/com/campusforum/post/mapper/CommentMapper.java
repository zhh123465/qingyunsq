package com.campusforum.post.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.post.domain.Comment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    @Select("SELECT * FROM comments WHERE deleted = 1 ${ew.customSqlSegment}")
    List<Comment> selectTrash(@Param(Constants.WRAPPER) Wrapper<Comment> ew);

    @Update("UPDATE comments SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    @Delete("DELETE FROM comments WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") Long id);

    /** 级联：物理删除某帖子下所有评论（含软删）。用于 Post.purge。 */
    @Delete("DELETE FROM comments WHERE post_id = #{postId}")
    int physicalDeleteByPostId(@Param("postId") Long postId);

    @Select("SELECT * FROM comments WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<Comment> selectTrashPage(IPage<Comment> page, @Param(Constants.WRAPPER) Wrapper<Comment> ew);

    @Update("<script>UPDATE comments SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE comments SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE comments SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
