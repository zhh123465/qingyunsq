package com.campusforum.announcement.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campusforum.announcement.domain.Announcement;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 前台"当前生效"公告：已发布 + 未删除 + 到达生效时间 + 未过期。
     * 多租户插件会自动追加 tenant_id 条件。
     */
    @Select("SELECT * FROM announcements " +
            "WHERE deleted = 0 " +
            "  AND status = 'published' " +
            "  AND (publish_time IS NULL OR publish_time <= #{now}) " +
            "  AND (expire_time  IS NULL OR expire_time  >  #{now}) " +
            "ORDER BY pinned DESC, publish_time DESC, id DESC " +
            "LIMIT 10")
    List<Announcement> selectActive(@Param("now") LocalDateTime now);

    /**
     * 前台分页列表：已发布 + 未删除，含未来生效 & 未过期。
     * 多租户插件自动追加 tenant_id；分页交给 MP PaginationInnerInterceptor。
     */
    @Select("SELECT * FROM announcements " +
            "WHERE deleted = 0 " +
            "  AND status = 'published' " +
            "  AND (expire_time IS NULL OR expire_time > #{now}) " +
            "ORDER BY pinned DESC, publish_time DESC, id DESC")
    IPage<Announcement> selectPublicPage(IPage<Announcement> page, @Param("now") LocalDateTime now);

    // === 回收站视图（deleted=1）===
    @Select("SELECT * FROM announcements WHERE deleted = 1 ${ew.customSqlSegment}")
    IPage<Announcement> selectTrashPage(IPage<Announcement> page, @Param(Constants.WRAPPER) Wrapper<Announcement> ew);

    @Update("UPDATE announcements SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);

    /** 物理删除（仅允许对已在回收站的记录）。 */
    @Delete("DELETE FROM announcements WHERE id = #{id} AND deleted = 1")
    int physicalDeleteById(@Param("id") Long id);

    // === 批量（写操作不被 @TableLogic 拦截）===

    @Update("<script>UPDATE announcements SET status = #{status} WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchSetStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    @Update("<script>UPDATE announcements SET deleted = 1 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    @Update("<script>UPDATE announcements SET deleted = 0 WHERE deleted = 1 AND id IN " +
            "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchRestore(@Param("ids") List<Long> ids);
}
