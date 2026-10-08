package com.campusforum.security.post;

import com.campusforum.achievement.service.AchievementService;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.notify.service.NotifyService;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.post.domain.Comment;
import com.campusforum.post.domain.Post;
import com.campusforum.post.dto.CreateCommentRequest;
import com.campusforum.post.mapper.CommentMapper;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.post.mapper.ReactionMapper;
import com.campusforum.post.service.CommentService;
import com.campusforum.qa.mapper.QaQuestionMapper;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommentService} XSS 净化单元测试（任务 T8.3 / 漏洞 18）。
 *
 * <p>验证评论创建链路在写库前调用 {@link HtmlSanitizerService#sanitizeComment(String)}
 * 剥离 {@code <script>} 等 XSS 载荷，避免评论区被滥用为 XSS 载体。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceXssTest {

    /** 评论持久层：被 mock，验证 insert 时传入的 Comment 内容已净化。 */
    @Mock
    private CommentMapper commentMapper;

    /** 帖子持久层：mock 出帖子查询 + 评论数原子递增。 */
    @Mock
    private PostMapper postMapper;

    @Mock
    private UserMapper userMapper;

    /** 通知服务：本用例不验证通知内容，让其变成 no-op。 */
    @Mock
    private NotifyService notifyService;

    @Mock
    private AchievementService achievementService;

    /** QA 扩展 mapper：评论非 QA 路径不会触发，但构造器需要。 */
    @Mock
    private QaQuestionMapper qaQuestionMapper;

    /** 反应（点赞 / 收藏）mapper：构造器需要。 */
    @Mock
    private ReactionMapper reactionMapper;

    /**
     * 敏感词服务：mock 成 0（无风险），让评论流程顺利走到 sanitize + insert。
     */
    @Mock
    private SensitiveWordService sensitiveWordService;

    @Mock
    private WebSocketBroadcaster webSocketBroadcaster;

    /** 真实 Sanitizer：OWASP Sanitizer 无状态，直接 new 验证真实剥离效果。 */
    private final HtmlSanitizerService htmlSanitizerService = new HtmlSanitizerService();

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        // 顺序与 CommentService 字段声明一致：
        // commentMapper / postMapper / userMapper / notifyService /
        // achievementService / qaQuestionMapper / reactionMapper /
        // sensitiveWordService / webSocketBroadcaster / htmlSanitizerService
        commentService = new CommentService(
                commentMapper, postMapper, userMapper, notifyService,
                achievementService, qaQuestionMapper, reactionMapper,
                sensitiveWordService, webSocketBroadcaster, htmlSanitizerService);

        // 默认敏感词风险 = 0，避免在 sanitize 之前抛异常
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(0);
    }

    @Test
    @DisplayName("script_inComment_isStripped：评论内容含 <script> 写库时被剥离")
    void script_inComment_isStripped() {
        // 帖子存在 + 帖子作者 = 当前用户（避免触发 COMMENT 通知额外分支）
        Post post = new Post();
        post.setAuthorId(1L);
        when(postMapper.selectById(10L)).thenReturn(post);
        // 注意：incrementCommentCount 第二参数是 int 原始类型，必须用 anyInt()，
        // 否则 any() 返回 null 会触发 NPE，且会污染后续测试的 matcher 状态。
        when(postMapper.incrementCommentCount(anyLong(), anyInt())).thenReturn(1);

        // 评论作者（用于 mentionParser 通知 senderName）
        User author = new User();
        author.setId(1L);
        author.setNickname("作者");
        when(userMapper.selectById(1L)).thenReturn(author);

        // commentMapper.insert：模拟自增 id 填充
        when(commentMapper.insert(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(99L);
            return 1;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setPostId(10L);
        req.setContent("<script>alert(1)</script>hello");

        commentService.create(1L, req);

        // 捕获 insert 的 Comment 入参，断言内容已净化
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(cap.capture());
        String stored = cap.getValue().getContent();

        assertThat(stored)
                .as("落库评论内容应剥离 <script> 标签")
                .doesNotContain("<script")
                .doesNotContain("alert(1)")
                .contains("hello");
    }
}
