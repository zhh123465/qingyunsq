package com.campusforum.security.message;

import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.message.domain.Message;
import com.campusforum.message.mapper.MessageMapper;
import com.campusforum.message.service.MessageService;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MessageService} XSS 净化单元测试（任务 T8.3 / 漏洞 18）。
 *
 * <p>验证私信发送链路在写库前调用 {@link HtmlSanitizerService#sanitizeMessage(String)}，
 * 剥离 {@code <script>} 等 XSS 载荷，避免私信成为存储型 XSS 的投递通道。</p>
 *
 * <p>本测试不启动 Spring 上下文 / 不连接 MySQL / Redis，使用 Mockito 注入真实
 * {@link HtmlSanitizerService}（无状态 Bean，可直接 new），其余依赖全部 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceXssTest {

    /** 私信落库 mapper：被 mock，验证 insert 时传入的 Message 内容已净化。 */
    @Mock
    private MessageMapper messageMapper;

    /** 用户 mapper：mock 出 senderId/receiverId 对应的 User 对象，绕过"接收人不存在"校验。 */
    @Mock
    private UserMapper userMapper;

    /** WebSocket 广播器：本用例不关心推送结果，mock 即可。 */
    @Mock
    private WebSocketBroadcaster webSocketBroadcaster;

    /**
     * 敏感词服务（任务 T8.10）：本用例不关心风险等级，mock 返回 0（安全）。
     */
    @Mock
    private SensitiveWordService sensitiveWordService;

    /** ObjectMapper 不 mock — Spring 单例的真实实现，序列化简单 Map。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 真实 {@link HtmlSanitizerService}：OWASP Sanitizer 无状态，
     * 用真实实例验证"输出确实剥离了 script"，比 mock 更接近线上行为。
     */
    private final HtmlSanitizerService htmlSanitizerService = new HtmlSanitizerService();

    /**
     * 被测对象。Lombok {@code @RequiredArgsConstructor} 生成的构造器顺序
     * 与字段声明顺序一致：messageMapper / userMapper / webSocketBroadcaster /
     * objectMapper / htmlSanitizerService。
     */
    private MessageService messageService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 手动构造，避免 @InjectMocks 对 final 字段顺序的猜测错误
        messageService = new MessageService(
                messageMapper, userMapper, webSocketBroadcaster, objectMapper, htmlSanitizerService, sensitiveWordService);
    }

    @Test
    @DisplayName("script_inMessage_isStripped：私信中含 <script> 写库时被剥离")
    void script_inMessage_isStripped() {
        // 接收方存在校验：mock 一个非 null 的 User
        User receiver = new User();
        receiver.setId(2L);
        receiver.setNickname("接收方");
        when(userMapper.selectById(2L)).thenReturn(receiver);
        // 发送方查询（用于 WS 推送 senderName）
        User sender = new User();
        sender.setId(1L);
        sender.setNickname("发送方");
        when(userMapper.selectById(1L)).thenReturn(sender);

        // insert 时填充自增 id 即可（业务后续 toVO 不依赖 id 精确值）
        when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(100L);
            return 1;
        });

        String evil = "<script>alert(1)</script>hi";
        messageService.send(1L, 2L, evil, null);

        // 捕获 insert 入参，断言内容已净化
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(cap.capture());
        String storedContent = cap.getValue().getContent();

        assertThat(storedContent)
                .as("落库私信内容应剥离 <script> 标签")
                .doesNotContain("<script")
                .doesNotContain("alert(1)")
                .contains("hi");
    }
}
