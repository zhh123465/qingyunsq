package com.campusforum.ai.workspace.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.ai.workspace.domain.AiNote;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiNoteMapper extends BaseMapper<AiNote> {

    /** 绕过 MyBatis-Plus 插件链，直接执行软删除，确保 deleted=1 被持久化。 */
    @Update("UPDATE ai_notes SET deleted = 1 WHERE id = #{id} AND deleted = 0")
    int deleteNoteDirect(@Param("id") String id);

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    // ai_notes 主键是 VARCHAR(32)（UUID），String 类型。
    @Select("SELECT * FROM ai_notes WHERE deleted = 1 ${ew.customSqlSegment}")
    List<AiNote> selectTrash(@Param(Constants.WRAPPER) Wrapper<AiNote> ew);

    @Update("UPDATE ai_notes SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") String id);

    @Delete("DELETE FROM ai_notes WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") String id);

    @Select("SELECT * FROM ai_notes WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<AiNote> selectTrashPage(IPage<AiNote> page, @Param(Constants.WRAPPER) Wrapper<AiNote> ew);

    @Update("<script>UPDATE ai_notes SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<String> ids);

    @Update("<script>UPDATE ai_notes SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<String> ids);

    /** status VARCHAR：draft/published/hidden。 */
    @Update("<script>UPDATE ai_notes SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<String> ids, @Param("status") String status);
}
