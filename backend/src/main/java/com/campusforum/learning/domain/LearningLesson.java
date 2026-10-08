package com.campusforum.learning.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_lessons")
public class LearningLesson {
    @TableId
    private String id;
    private String tutorialId;
    private String title;
    private String content;
    private String contentOssKey;
    private String sourceUrl;
    private Integer orderIndex;
    private LocalDateTime createdAt;
}
