package com.campusforum.checkin.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.checkin.domain.CheckinChallenge;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CheckinChallengeMapper extends BaseMapper<CheckinChallenge> {

    // === 回收站 / 恢复 / 彻底删除（管理端）===
    // checkin_challenges 表没有 deleted 字段（schema.sql），所以这里"回收站"
    // 的语义借用 status=0 作为已删除标记，回收站视图列出 status=0 的挑战；
    // 恢复即把 status 改回 1，彻底删除走物理 DELETE + 级联清理 checkin_records。
    @Select("SELECT * FROM checkin_challenges WHERE status = 0 ${ew.customSqlSegment}")
    List<CheckinChallenge> selectTrash(@Param(Constants.WRAPPER) Wrapper<CheckinChallenge> ew);

    @Update("UPDATE checkin_challenges SET status = 1 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    @Delete("DELETE FROM checkin_challenges WHERE id = #{id} AND status = 0")
    int physicalDeleteById(@Param("id") Long id);

    @Select("SELECT * FROM checkin_challenges WHERE status = 0 ${ew.customSqlSegment}")
    IPage<CheckinChallenge> selectTrashPage(IPage<CheckinChallenge> page, @Param(Constants.WRAPPER) Wrapper<CheckinChallenge> ew);

    /** 批量删除到回收站：status = 0（对齐 selectTrash 语义）。 */
    @Update("<script>UPDATE checkin_challenges SET status = 0 WHERE status &lt;&gt; 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    /** 批量恢复：status 从 0 恢复到 1（未开始）。 */
    @Update("<script>UPDATE checkin_challenges SET status = 1 WHERE status = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);

    /** 批量改状态：显式指定，通常是 1（正常）/ 2（隐藏，如果扩展）。回收站(0) 不走该端点。 */
    @Update("<script>UPDATE checkin_challenges SET status = #{status} WHERE status &lt;&gt; 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
