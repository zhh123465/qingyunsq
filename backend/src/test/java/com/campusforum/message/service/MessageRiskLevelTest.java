package com.campusforum.message.service;

import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.message.domain.Message;
import com.campusforum.message.mapper.MessageMapper;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务 T8.10 / 漏洞 16：私信风险等级落库测试。
 *
 * <p>验证 {@code MessageService.send} 在写库前调用
 * {@link SensitiveWordService#getRiskLevel(String)} 并把结果写入
 * {@code messages.ai_risk_level} 字段。</p>
 *
 * <p>纯单元测试：不启动 Spring 上下文 / 不连 MySQL / Redis；
 * 真实使用 OWASP {@link HtmlSanitizerService}（无状态 Bean）以验证
 * "评级基于已净化后的内容"这一安全策略；其余依赖全部 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageRiskLevelTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private WebSocketBroadcaster webSocketBroadcaster;

    @Mock
    private SensitiveWordService sensitiveWordService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HtmlSanitizerService htmlSanitizerService = new HtmlSanitizerService();

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageMapper, userMapper, webSocketBroadcaster, objectMapper,
                htmlSanitizerService, sensitiveWordService);

        // 默认 mock：sender / receiver 存在，避免业务前置校验中断
        User sender = new User();
        sender.setId(1L);
        sender.setNickname("发送方");
        User receiver = new User();
        receiver.setId(2L);
        receiver.setNickname("接收方");
        when(userMapper.selectById(1L)).thenReturn(sender);
        when(userMapper.selectById(2L)).thenReturn(receiver);

        when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(101L);
            return 1;
        });
    }

    @Test
    @DisplayName("safeContent_setsLevel0：非敏感内容写入 ai_risk_level=0")
    void safeContent_setsLevel0() {
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(0);

        messageService.send(1L, 2L, "你好，今天天气不错", null);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getAiRiskLevel())
                .as("非敏感内容应写入 0")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("sensitiveContent_setsLevel1：疑似命中写入 ai_risk_level=1")
    void sensitiveContent_setsLevel1() {
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(1);

        messageService.send(1L, 2L, "包含疑似敏感词的私信", null);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getAiRiskLevel())
                .as("疑似命中应写入 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("violatingContent_setsLevel2：违规命中写入 ai_risk_level=2")
    void violatingContent_setsLevel2() {
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(2);

        messageService.send(1L, 2L, "命中违规词的私信", null);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getAiRiskLevel())
                .as("违规命中应写入 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("riskLevel_isEvaluatedOnSanitizedContent：评级基于已净化内容（不含 <script>）")
    void riskLevel_isEvaluatedOnSanitizedContent() {
        // 关键安全断言：传给 SensitiveWordService 的内容必须先经 HTML 净化，
        // 否则攻击者构造的 <script>safeWord</script> 会让"safeWord"被错评。
        when(sensitiveWordService.getRiskLevel(anyString())).thenReturn(0);

        String evil = "<script>alert(1)</script>正常内容";
        messageService.send(1L, 2L, evil, null);

        ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
        verify(sensitiveWordService).getRiskLevel(arg.capture());
        String evaluated = arg.getValue();

        assertThat(evaluated)
                .as("评级输入应已剥离 <script>")
                .doesNotContain("<script")
                .doesNotContain("alert(1)")
                .contains("正常内容");
    }
}
