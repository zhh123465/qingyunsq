package com.campusforum.checkin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.checkin.domain.CheckinRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CheckinRecordMapper extends BaseMapper<CheckinRecord> {

    /** 级联：物理删除某挑战下所有打卡记录。用于 CheckinChallenge.purge。 */
    @Delete("DELETE FROM checkin_records WHERE challenge_id = #{challengeId}")
    int physicalDeleteByChallengeId(@Param("challengeId") Long challengeId);
}
