package com.campusforum.learning.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_tutorials")
public class LearningTutorial {
    @TableId
    private String id;
    private String title;
    private String slug;
    private String description;
    private String source;
    private String sourceUrl;
    private String category;
    private String icon;
    private Integer lessonCount;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
