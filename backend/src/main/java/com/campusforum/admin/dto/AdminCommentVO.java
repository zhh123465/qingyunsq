package com.campusforum.admin.dto;

import com.campusforum.user.dto.PublicUserVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端评论列表 VO：比前台 CommentVO 多了 status / postTitle，
 * 用于表格展示"状态"列与快速跳到帖子详情页。
 */
@Data
@Builder
public class AdminCommentVO {
    private Long id;
    private Long postId;
    private String postTitle;
    private Long parentId;
    private Long replyToId;
    private Long authorId;
    private PublicUserVO author;
    private String content;
    private Integer likeCount;
    private Integer status;
    private LocalDateTime createdAt;
}
