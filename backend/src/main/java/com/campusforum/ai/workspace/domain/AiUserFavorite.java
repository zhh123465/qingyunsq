package com.campusforum.ai.workspace.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_user_favorites")
public class AiUserFavorite {
    private Long userId;
    private String favoriteType;  // agent | knowledge_base
    private String targetId;
    private LocalDateTime createdAt;
}
