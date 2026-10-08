package com.campusforum.space.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.space.domain.Space;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SpaceMapper extends BaseMapper<Space> {

    @Update("UPDATE spaces SET member_count = member_count + #{delta} WHERE id = #{spaceId}")
    int incrementMemberCount(@Param("spaceId") Long spaceId, @Param("delta") int delta);

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    @Select("SELECT * FROM spaces WHERE deleted = 1 ${ew.customSqlSegment}")
    List<Space> selectTrash(@Param(Constants.WRAPPER) Wrapper<Space> ew);

    @Update("UPDATE spaces SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    @Delete("DELETE FROM spaces WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") Long id);

    @Select("SELECT * FROM spaces WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<Space> selectTrashPage(IPage<Space> page, @Param(Constants.WRAPPER) Wrapper<Space> ew);

    @Update("<script>UPDATE spaces SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE spaces SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE spaces SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
