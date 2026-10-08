package com.campusforum.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusforum.learning.domain.LearningLesson;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {}
