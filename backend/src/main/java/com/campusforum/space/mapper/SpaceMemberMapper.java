package com.campusforum.space.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.space.domain.SpaceMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SpaceMemberMapper extends BaseMapper<SpaceMember> {

    /** 级联：物理删除某空间下所有成员关系。用于 Space.purge。 */
    @Delete("DELETE FROM space_members WHERE space_id = #{spaceId}")
    int physicalDeleteBySpaceId(@Param("spaceId") Long spaceId);
}
