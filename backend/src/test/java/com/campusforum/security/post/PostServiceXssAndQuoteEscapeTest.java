package com.campusforum.security.post;

import com.campusforum.achievement.service.AchievementService;
import com.campusforum.follow.service.FollowService;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.infra.security.TrustedProxyResolver;
import com.campusforum.notify.service.NotifyService;
import com.campusforum.post.domain.Post;
import com.campusforum.post.dto.CreatePostRequest;
import com.campusforum.ai.mapper.PostAiCardMapper;
import com.campusforum.post.mapper.CommentMapper;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.post.mapper.ReactionMapper;
import com.campusforum.post.service.PostService;
import com.campusforum.qa.mapper.QaQuestionMapper;
import com.campusforum.search.service.MeiliSearchClient;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.campusforum.space.mapper.SpaceMemberMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import com.campusforum.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PostService} XSS 净化 + 引用块 Markdown 转义单元测试
 * （任务 T8.3 / 漏洞 18 + 漏洞 20）。
 *
 * <p>验证两个核心修复行为：</p>
 * <ol>
 *   <li>create() 写库前调 {@link HtmlSanitizerService#sanitizePost(String)}
 *       剥离 {@code <script>} 等 XSS 载荷（漏洞 18）；</li>
 *   <li>create() 拼接引用块时对 nickname / title / content 调
 *       {@code MarkdownEscaper.escape}，避免恶意昵称破出引用块边界（漏洞 20）。</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 上下文 / 不连接数据库或 Redis；除真实
 * {@link HtmlSanitizerService}（无状态可直接 new）外，全部协作者使用
 * Mockito mock 注入。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostServiceXssAndQuoteEscapeTest {

    /** 帖子持久层：被 mock，验证 insert 时传入的 Post 内容已净化 / 转义。 */
    @Mock
    private PostMapper postMapper;

    @Mock
    private ReactionMapper reactionMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private QaQuestionMapper qaQuestionMapper;

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private PostAiCardMapper postAiCardMapper;

    @Mock
    private NotifyService notifyService;

    @Mock
    private AchievementService achievementService;

    @Mock
    private MeiliSearchClient meiliSearchClient;

    @Mock
    private SensitiveWordService sensitiveWordService;

    @Mock
    private FollowService followService;

    @Mock
    private UserService userService;

    @Mock
    private SpaceMemberMapper spaceMemberMapper;

    @Mock
    private com.campusforum.post.service.PostViewDeduper postViewDeduper;

    @Mock
    private TrustedProxyResolver trustedProxyResolver;

    @Mock
    private HttpServletRequest httpRequest;

    /** 真实 Sanitizer：OWASP Sanitizer 无状态，直接 new 验证真实剥离效果。 */
    private final HtmlSanitizerService htmlSanitizerService = new HtmlSanitizerService();

    /** 被测对象。 */
    private PostService postService;

    @BeforeEach
    void setUp() {
        // Lombok @RequiredArgsConstructor 生成构造器，参数顺序与字段声明顺序严格一致：
        // postMapper / reactionMapper / userMapper / qaQuestionMapper / commentMapper /
        // postAiCardMapper / notifyService / achievementService / meiliSearchClient /
        // sensitiveWordService / followService / userService / spaceMemberMapper /
        // postViewDeduper / trustedProxyResolver / httpRequest / htmlSanitizerService
        postService = new PostService(
                postMapper, reactionMapper, userMapper, qaQuestionMapper,
                commentMapper, postAiCardMapper,
                notifyService, achievementService, meiliSearchClient, sensitiveWordService,
                followService, userService, spaceMemberMapper, postViewDeduper,
                trustedProxyResolver, httpRequest, htmlSanitizerService);

        // 默认敏感词风险 = 0，让 create 流程顺利走到 sanitize + insert
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(0);
        // 标签订阅查询：默认空集合，避免 NPE
        when(userService.findSubscribedUserIds(any())).thenReturn(Set.of());
        // postMapper.insert：模拟自增 id 填充
        when(postMapper.insert(any(Post.class))).thenAnswer(inv -> {
            Post p = inv.getArgument(0);
            p.setId(1000L);
            return 1;
        });
    }

    /**
     * 漏洞 18 验证：create() 中含 {@code <script>} 的内容写库时被剥离。
     */
    @Test
    @DisplayName("xssScript_isStripped_onCreate：create 时 <script> 写库被剥离")
    void xssScript_isStripped_onCreate() {
        // 构造无空间、无引用的最简单创建请求
        CreatePostRequest req = new CreatePostRequest();
        req.setScope("SQUARE");
        req.setType("NORMAL");
        req.setTitle("T");
        req.setContent("<script>alert(1)</script>hi");

        postService.create(1L, req);

        // 捕获 insert 入参，断言落库 content 已剥离 <script>
        ArgumentCaptor<Post> cap = ArgumentCaptor.forClass(Post.class);
        verify(postMapper).insert(cap.capture());
        String stored = cap.getValue().getContent();

        assertThat(stored)
                .as("落库帖子内容应剥离 <script> 标签")
                .doesNotContain("<script")
                .doesNotContain("alert(1)")
                .contains("hi");
    }

    /**
     * 漏洞 20 验证：恶意 nickname 含 markdown 控制字符（粗体 / 标题 / 引用）时，
     * 拼接后的引用块不能被破坏 — 必须以转义形式（如 {@code \*\*X\*\*}）落库。
     */
    @Test
    @DisplayName("evilNickname_doesNotEscapeQuoteBlock：恶意昵称被 Markdown 转义")
    void evilNickname_doesNotEscapeQuoteBlock() {
        // 准备被引用帖子
        Post quoted = new Post();
        quoted.setId(99L);
        quoted.setAuthorId(2L);
        quoted.setTitle("原帖标题");
        quoted.setContent("原帖正文");
        quoted.setDeleted(0);
        when(postMapper.selectById(99L)).thenReturn(quoted);

        // 准备被引用作者：昵称含 markdown 控制字符 — 期望被 MarkdownEscaper.escape 全部转义
        User evilAuthor = new User();
        evilAuthor.setId(2L);
        evilAuthor.setNickname("**X**\n# H1\n>");
        when(userMapper.selectById(2L)).thenReturn(evilAuthor);

        // 当前发帖人（mention 通知用）
        User me = new User();
        me.setId(1L);
        me.setNickname("我");
        when(userMapper.selectById(1L)).thenReturn(me);

        // 引用创建请求
        CreatePostRequest req = new CreatePostRequest();
        req.setScope("SQUARE");
        req.setType("NORMAL");
        req.setTitle("我的回应");
        req.setContent("我的内容");
        req.setQuotePostId(99L);

        postService.create(1L, req);

        // 捕获 insert 入参，断言昵称中的 markdown 控制字符已被转义
        ArgumentCaptor<Post> cap = ArgumentCaptor.forClass(Post.class);
        verify(postMapper).insert(cap.capture());
        String stored = cap.getValue().getContent();

        // 1) 必须以转义形式包含昵称：`\*\*X\*\*`（Java 字面量需要 4 个反斜杠 + 星号）
        assertThat(stored)
                .as("引用块中的昵称必须以 Markdown 转义形式落库，不能保留可触发粗体的 **")
                .contains("\\*\\*X\\*\\*");

        // 2) 用户原始正文必须保留
        assertThat(stored).contains("我的内容");

        // 3) 帖子类型应被改写为 QUOTE
        assertThat(cap.getValue().getType()).isEqualTo("QUOTE");
    }
}
