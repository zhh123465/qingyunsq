package com.campusforum.post.service;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.admin.dto.AdminCommentVO;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.achievement.service.AchievementService;
import com.campusforum.notify.service.NotifyService;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.post.domain.Comment;
import com.campusforum.post.domain.Post;
import com.campusforum.post.domain.Reaction;
import com.campusforum.post.dto.CommentVO;
import com.campusforum.post.dto.CreateCommentRequest;
import com.campusforum.post.dto.ReactionRequest;
import com.campusforum.post.dto.UpdateCommentRequest;
import com.campusforum.post.mapper.CommentMapper;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.post.mapper.ReactionMapper;
import com.campusforum.qa.domain.QaQuestion;
import com.campusforum.qa.mapper.QaQuestionMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import com.campusforum.infra.websocket.BroadcastMessage;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final NotifyService notifyService;
    private final AchievementService achievementService;
    private final QaQuestionMapper qaQuestionMapper;
    private final ReactionMapper reactionMapper;
    private final SensitiveWordService sensitiveWordService;
    private final WebSocketBroadcaster webSocketBroadcaster;

    /** Self reference for AOP self-invocation (per-item transaction in batch purge). */
    @Resource
    @Lazy
    private CommentService selfProxy;
    /**
     * HTML 净化服务（任务 T8.3 / 漏洞 18）：评论 / 回复内容写库前剥离 {@code <script>} /
     * 事件处理属性 / {@code javascript:} 协议 URL，避免存储型 XSS。
     * COMMENT_POLICY 仅允许格式化与链接，不允许图片 / 表格 / 代码块。
     */
    private final HtmlSanitizerService htmlSanitizerService;
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    @Transactional
    public CommentVO create(Long userId, CreateCommentRequest req) {
        // 敏感词过滤：创建前检查
        int riskLevel = sensitiveWordService.getRiskLevel(req.getContent());
        if (riskLevel >= 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "评论包含敏感内容，请修改后重试");
        }

        Comment comment = new Comment();
        comment.setPostId(req.getPostId());
        comment.setParentId(req.getParentId());
        comment.setReplyToId(req.getReplyToId());
        comment.setAuthorId(userId);
        // 漏洞 18 修复：评论写库前必须经 HTML 净化，移除 <script> / 事件处理属性 /
        // javascript: 协议 URL，避免评论区被滥用为 XSS 载体。
        comment.setContent(htmlSanitizerService.sanitizeComment(req.getContent()));
        comment.setLikeCount(0);
        comment.setStatus(1);

        commentMapper.insert(comment);
        achievementService.onCommentCreated(userId);

        // Bug fix 1.4: 原子更新帖子评论数
        postMapper.incrementCommentCount(req.getPostId(), 1);

        // 通知 + WebSocket 推送评论变更事件
        var post = postMapper.selectById(req.getPostId());
        User commenter = userMapper.selectById(userId);
        String commenterName = commenter != null ? commenter.getNickname() : "有人";

        if (post != null && !post.getAuthorId().equals(userId)) {
            // 评论帖子，通知帖子作者
            notifyService.create(post.getAuthorId(), userId, "COMMENT",
                    "评论通知", commenterName + " 评论了你的帖子", "/posts/" + post.getId());
        }

        Comment parentComment = null;
        if (req.getParentId() != null) {
            // Bug fix 1.18: 显式空值检查
            parentComment = commentMapper.selectById(req.getParentId());
            if (parentComment == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "父评论不存在");
            }
        }

        Comment replyTargetComment = null;
        if (req.getReplyToId() != null) {
            replyTargetComment = commentMapper.selectById(req.getReplyToId());
            if (replyTargetComment == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "回复目标不存在");
            }
        }

        Comment notifyTargetComment = replyTargetComment != null ? replyTargetComment : parentComment;
        if (notifyTargetComment != null && !notifyTargetComment.getAuthorId().equals(userId)) {
            notifyService.create(notifyTargetComment.getAuthorId(), userId, "REPLY",
                    "回复通知", commenterName + " 回复了你", "/posts/" + req.getPostId());
        }

        // 解析 @提及 并发送通知
        Set<String> mentionedNames = MentionParser.extract(req.getContent());
        for (String name : mentionedNames) {
            // 同昵称可能存在多个用户（不强制唯一），这里限定查询取第一条，避免 selectOne 抛 TooManyResults
            List<User> matches = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .eq(User::getNickname, name)
                    .last("LIMIT 1"));
            User mentioned = matches.isEmpty() ? null : matches.get(0);
            if (mentioned != null && !mentioned.getId().equals(userId)) {
                notifyService.create(mentioned.getId(), userId, "MENTION",
                        "提及通知", commenterName + " @了你", "/posts/" + req.getPostId());
            }
        }

        log.info("Comment created: id={}, postId={}", comment.getId(), req.getPostId());

        // 广播评论变更事件到帖子作者（用于实时刷新评论区）
        if (post != null) {
            broadcastCommentChange(post.getId(), post.getAuthorId(), "NEW_COMMENT");
        }

        return toVO(comment);
    }

    public List<CommentVO> listByPost(Long postId, Long cursor, int limit, boolean qaSort) {
        int size = Math.min(limit, 100);
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<>();
        qw.eq(Comment::getPostId, postId);
        qw.isNull(Comment::getParentId);
        qw.eq(Comment::getStatus, 1);

        // QA 模式：按分数排序，已采纳答案置顶
        if (qaSort) {
            Long acceptedId = null;
            QaQuestion qa = qaQuestionMapper.selectOne(new LambdaQueryWrapper<QaQuestion>()
                    .eq(QaQuestion::getPostId, postId));
            if (qa != null && qa.getAcceptedCommentId() != null) {
                acceptedId = qa.getAcceptedCommentId();
            }

            // 加载全部顶层评论（QA 帖子回答数有限，全量加载排序）
            List<Comment> allParents = commentMapper.selectList(qw);
            final Long finalAcceptedId = acceptedId;
            allParents.sort((a, b) -> {
                // 已采纳置顶
                boolean aAcc = a.getId().equals(finalAcceptedId);
                boolean bAcc = b.getId().equals(finalAcceptedId);
                if (aAcc && !bAcc) return -1;
                if (!aAcc && bAcc) return 1;
                // 按点赞数降序
                int scoreCmp = Integer.compare(
                        b.getLikeCount() != null ? b.getLikeCount() : 0,
                        a.getLikeCount() != null ? a.getLikeCount() : 0);
                if (scoreCmp != 0) return scoreCmp;
                // 同分按 ID 升序（早回答在前）
                return Long.compare(a.getId(), b.getId());
            });

            List<Comment> parents = allParents;
            if (parents.isEmpty()) return List.of();

            return attachFlatReplies(postId, parents);
        }

        // 普通模式：按 ID 游标分页
        if (cursor != null) {
            qw.gt(Comment::getId, cursor);
        }
        qw.orderByAsc(Comment::getId);
        qw.last("LIMIT " + size);

        List<Comment> parents = commentMapper.selectList(qw);
        if (parents.isEmpty()) return List.of();

        return attachFlatReplies(postId, parents);
    }

    private List<CommentVO> attachFlatReplies(Long postId, List<Comment> parents) {
        Set<Long> parentIds = parents.stream().map(Comment::getId).collect(Collectors.toSet());
        LambdaQueryWrapper<Comment> childQw = new LambdaQueryWrapper<>();
        childQw.eq(Comment::getPostId, postId);
        childQw.isNotNull(Comment::getParentId);
        childQw.eq(Comment::getStatus, 1);
        childQw.orderByAsc(Comment::getId);
        List<Comment> children = commentMapper.selectList(childQw);

        Map<Long, Comment> commentById = new HashMap<>();
        parents.forEach(comment -> commentById.put(comment.getId(), comment));
        children.forEach(comment -> commentById.put(comment.getId(), comment));

        // 评论区只允许主评论下面有一个回复区。这里把历史上可能存在的多级回复
        // 全部沿 parentId 向上追溯到当前页主评论，然后压平成主评论的 replies。
        Map<Long, List<CommentVO>> repliesByRoot = new HashMap<>();
        for (Comment child : children) {
            Long rootId = findRootCommentId(child, commentById, parentIds);
            if (rootId == null) {
                continue;
            }
            repliesByRoot.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(toVO(child));
        }

        return parents.stream().map(parent -> {
            CommentVO vo = toVO(parent);
            vo.setReplies(repliesByRoot.getOrDefault(parent.getId(), List.of()));
            return vo;
        }).toList();
    }

    private Long findRootCommentId(Comment comment, Map<Long, Comment> commentById, Set<Long> rootIds) {
        Long parentId = comment.getParentId();
        Set<Long> visited = new HashSet<>();

        // 防御异常数据形成的 parentId 环，避免评论列表接口因为脏数据无限循环。
        while (parentId != null && visited.add(parentId)) {
            if (rootIds.contains(parentId)) {
                return parentId;
            }

            Comment parent = commentById.get(parentId);
            if (parent == null) {
                return null;
            }
            parentId = parent.getParentId();
        }

        return null;
    }

    @Transactional
    public void deleteComment(Long userId, Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        String role = (String) StpUtil.getSession().get("role");
        if (!comment.getAuthorId().equals(userId) && !"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        commentMapper.deleteById(commentId);
        log.info("Comment deleted: id={}", commentId);

        // 原子递减帖子评论数
        postMapper.incrementCommentCount(comment.getPostId(), -1);

        // 广播评论变更事件
        broadcastCommentChange(comment.getPostId(), comment.getAuthorId(), "COMMENT_DELETED");
    }

    @Transactional
    public CommentVO updateComment(Long userId, Long commentId, UpdateCommentRequest req) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (!comment.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权编辑此评论");
        }

        // 敏感词过滤
        int riskLevel = sensitiveWordService.getRiskLevel(req.getContent());
        if (riskLevel > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "评论包含敏感内容，请修改后重试");
        }

        comment.setContent(htmlSanitizerService.sanitizeComment(req.getContent()));
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);

        log.info("Comment updated: id={}, authorId={}", commentId, userId);
        return toVO(comment);
    }

    @Transactional
    public boolean toggleReaction(Long userId, Long commentId, ReactionRequest req) {
        if (!"LIKE".equals(req.getType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeleted() == 1 || comment.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }

        LambdaQueryWrapper<Reaction> qw = new LambdaQueryWrapper<>();
        qw.eq(Reaction::getUserId, userId)
                .eq(Reaction::getTargetType, "COMMENT")
                .eq(Reaction::getTargetId, commentId)
                .eq(Reaction::getType, req.getType());

        Reaction existing = reactionMapper.selectOne(qw);
        if (existing != null) {
            reactionMapper.deleteById(existing.getId());
            comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
            commentMapper.updateById(comment);
            return false;
        }

        Reaction reaction = new Reaction();
        reaction.setUserId(userId);
        reaction.setTargetType("COMMENT");
        reaction.setTargetId(commentId);
        reaction.setType(req.getType());
        reactionMapper.insert(reaction);

        comment.setLikeCount(comment.getLikeCount() + 1);
        commentMapper.updateById(comment);

        User liker = userMapper.selectById(userId);
        String likerName = liker != null ? liker.getNickname() : "有人";
        notifyService.create(comment.getAuthorId(), userId, "LIKE",
                "点赞通知", likerName + " 赞了你的评论", "/posts/" + comment.getPostId());

        return true;
    }

    /**
     * 广播评论变更事件到正在查看该帖子的用户。
     * 通过 WebSocket 推送 COMMENT_CHANGE 事件，前端收到后自动刷新评论列表。
     */
    private void broadcastCommentChange(Long postId, Long postAuthorId, String action) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", "COMMENT_CHANGE",
                    "postId", postId,
                    "action", action
            );
            String json = jsonMapper.writeValueAsString(payload);
            // 通知帖子作者（如果在线，经 Redis pub/sub 跨实例广播）
            webSocketBroadcaster.broadcast(new BroadcastMessage(postAuthorId, "COMMENT_CHANGE", json));
        } catch (Exception e) {
            log.debug("Failed to broadcast comment change: {}", e.getMessage());
        }
    }

    private CommentVO toVO(Comment comment) {
        User author = userMapper.selectById(comment.getAuthorId());
        PublicUserVO authorVO = PublicUserVO.from(author);

        return CommentVO.builder()
                .id(comment.getId())
                .postId(comment.getPostId())
                .parentId(comment.getParentId())
                .replyToId(comment.getReplyToId())
                .authorId(comment.getAuthorId())
                .author(authorVO)
                .content(comment.getContent())
                .likeCount(comment.getLikeCount())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    // === 管理端：列表 / 隐藏 / 逻辑删 / 回收站恢复 / 彻底删除 ===

    public IPage<AdminCommentVO> listForAdminPaged(String keyword, Long postId, Long authorId,
                                                   Integer status, long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<Comment> page = new Page<>(pageNum, size);
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like(Comment::getContent, keyword);
        if (postId != null) qw.eq(Comment::getPostId, postId);
        if (authorId != null) qw.eq(Comment::getAuthorId, authorId);
        if (status != null) qw.eq(Comment::getStatus, status);
        qw.orderByDesc(Comment::getId);
        IPage<Comment> res = commentMapper.selectPage(page, qw);
        return res.convert(this::toAdminVO);
    }

    public IPage<AdminCommentVO> listTrashForAdminPaged(String keyword, Long postId, Long authorId,
                                                       long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<Comment> page = new Page<>(pageNum, size);
        QueryWrapper<Comment> qw = new QueryWrapper<>();
        qw.lambda().like(keyword != null && !keyword.isBlank(), Comment::getContent, keyword)
                .eq(postId != null, Comment::getPostId, postId)
                .eq(authorId != null, Comment::getAuthorId, authorId)
                .orderByDesc(Comment::getId);
        IPage<Comment> res = commentMapper.selectTrashPage(page, qw);
        return res.convert(this::toAdminVO);
    }

    @Transactional
    public void setStatusForAdmin(Long commentId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值");
        }
        Comment c = commentMapper.selectById(commentId);
        if (c == null || c.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        c.setStatus(status);
        commentMapper.updateById(c);
    }

    @Transactional
    public void deleteByAdmin(Long commentId) {
        Comment c = commentMapper.selectById(commentId);
        if (c == null || c.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        commentMapper.deleteById(commentId);
        postMapper.incrementCommentCount(c.getPostId(), -1);
    }

    @Transactional
    public void restoreForAdmin(Long commentId) {
        int rows = commentMapper.restoreById(commentId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 恢复时把评论数补回，尽量对齐真实计数（若帖子被 purge 则忽略）
        Comment c = commentMapper.selectById(commentId);
        if (c != null) {
            postMapper.incrementCommentCount(c.getPostId(), 1);
        }
    }

    /** 彻底删除评论 + 相关点赞。 */
    @Transactional
    public Map<String, Integer> purgeForAdmin(Long commentId) {
        int reactions = reactionMapper.physicalDeleteByTarget("COMMENT", commentId);
        int self = commentMapper.physicalDeleteById(commentId);
        if (self == 0) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND.getCode(),
                    "评论不在回收站或已被清理");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("reactions", reactions);
        return counts;
    }

    // === 批量管理端操作 ===
    public int setStatusBatchForAdmin(List<Long> ids, int status) {
        return commentMapper.batchSetStatus(ids, status);
    }

    public int deleteBatchForAdmin(List<Long> ids) {
        // 需要减 post.comment_count；先查出即将被删的评论对应的 postId 计数分布
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>()
                .in(Comment::getId, ids)
                .eq(Comment::getDeleted, 0);
        List<Comment> before = commentMapper.selectList(qw);
        Map<Long, Long> byPost = before.stream()
                .collect(Collectors.groupingBy(Comment::getPostId, Collectors.counting()));
        int rows = commentMapper.batchLogicalDelete(ids);
        byPost.forEach((pid, cnt) -> postMapper.incrementCommentCount(pid, -cnt.intValue()));
        return rows;
    }

    public int restoreBatchForAdmin(List<Long> ids) {
        int rows = commentMapper.batchRestore(ids);
        // 恢复后按 postId 汇总补回计数（走 selectList，走 @TableLogic 自动 deleted=0）
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>().in(Comment::getId, ids);
        List<Comment> after = commentMapper.selectList(qw);
        Map<Long, Long> byPost = after.stream()
                .collect(Collectors.groupingBy(Comment::getPostId, Collectors.counting()));
        byPost.forEach((pid, cnt) -> postMapper.incrementCommentCount(pid, cnt.intValue()));
        return rows;
    }

    public Map<String, Object> purgeBatchForAdmin(List<Long> ids) {
        int success = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Integer> totalCounts = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                Map<String, Integer> c = selfProxy.purgeForAdmin(id);
                success++;
                c.forEach((k, v) -> totalCounts.merge(k, v, Integer::sum));
            } catch (BusinessException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", e.getMessage() != null ? e.getMessage() : "PURGE_FAILED");
                failed.add(f);
            } catch (Exception e) {
                log.warn("purge comment failed: id={}, msg={}", id, e.getMessage());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", "PURGE_FAILED");
                failed.add(f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("counts", totalCounts);
        return result;
    }

    private AdminCommentVO toAdminVO(Comment comment) {
        User author = userMapper.selectById(comment.getAuthorId());
        PublicUserVO authorVO = PublicUserVO.from(author);
        Post post = postMapper.selectById(comment.getPostId());
        String postTitle = post != null ? post.getTitle() : null;
        return AdminCommentVO.builder()
                .id(comment.getId())
                .postId(comment.getPostId())
                .postTitle(postTitle)
                .parentId(comment.getParentId())
                .replyToId(comment.getReplyToId())
                .authorId(comment.getAuthorId())
                .author(authorVO)
                .content(comment.getContent())
                .likeCount(comment.getLikeCount())
                .status(comment.getStatus())
                .createdAt(comment.getCreatedAt())
                .build();
    }
}
