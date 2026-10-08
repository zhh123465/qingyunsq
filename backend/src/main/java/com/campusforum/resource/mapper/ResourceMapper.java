package com.campusforum.resource.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.resource.domain.Resource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {

    /** 原子自增下载计数，避免并发下载丢失更新。 */
    @Update("UPDATE resources SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownloadCount(@Param("id") Long id);

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    @Select("SELECT * FROM resources WHERE deleted = 1 ${ew.customSqlSegment}")
    List<Resource> selectTrash(@Param(Constants.WRAPPER) Wrapper<Resource> ew);

    @Update("UPDATE resources SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    @Delete("DELETE FROM resources WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") Long id);

    @Select("SELECT * FROM resources WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<Resource> selectTrashPage(IPage<Resource> page, @Param(Constants.WRAPPER) Wrapper<Resource> ew);

    @Update("<script>UPDATE resources SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE resources SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE resources SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
