package com.campusforum.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.message.domain.Message;
import com.campusforum.message.dto.MessageVO;
import com.campusforum.message.mapper.MessageMapper;
import com.campusforum.infra.websocket.BroadcastMessage;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.sensitive.service.SensitiveWordService;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final WebSocketBroadcaster webSocketBroadcaster;
    private final ObjectMapper objectMapper;
    /**
     * HTML 净化服务（任务 T8.3 / 漏洞 18）：私信内容写库前剥离 {@code <script>} /
     * 事件处理属性 / {@code javascript:} 协议 URL，避免私信成为存储型 XSS 载体。
     * 私信沿用与评论相同的 COMMENT_POLICY（仅格式化 + 链接）。
     */
    private final HtmlSanitizerService htmlSanitizerService;
    /**
     * 敏感词风险评级服务（任务 T8.10 / 漏洞 16）：私信写库前调用
     * {@link SensitiveWordService#getRiskLevel(String)} 取 0/1/2 级别，
     * 持久化为 {@code messages.ai_risk_level} 供后台风控筛选与统计。
     * 注意：该等级不影响"是否拒绝写入"的策略决策（已由 sanitizer 兜底），
     * 仅作为审计 / 处置维度。
     */
    private final SensitiveWordService sensitiveWordService;

    @Transactional
    public MessageVO send(Long senderId, Long receiverId, String content, String imageUrl) {
        if (senderId.equals(receiverId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "不能给自己发私信");
        }
        User receiver = userMapper.selectById(receiverId);
        if (receiver == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        Message msg = new Message();
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        // 漏洞 18 修复：私信写库前必须经 HTML 净化，避免攻击者通过私信投递 XSS 载荷
        // 给目标用户（接收方在 IM 弹窗 / 通知面板渲染时也会触发同样风险）。
        String sanitized = htmlSanitizerService.sanitizeMessage(content);
        msg.setContent(sanitized);
        msg.setImageUrl(imageUrl);
        // 任务 T8.10 / 漏洞 16：基于"已净化后的内容"评估风险等级，避免攻击者通过
        // <script> 等危险 token 自带的字符触发误评（净化后 token 已被剥离）。
        // getRiskLevel 内部已对原文做归一化（漏洞 27），同时对 null 做容错。
        int riskLevel = sensitiveWordService.getRiskLevel(sanitized);
        msg.setAiRiskLevel(riskLevel);
        msg.setIsRead(0);
        messageMapper.insert(msg);

        // Bug fix 1.9: 使用 ObjectMapper 安全构造 JSON，防止注入
        try {
            User sender = userMapper.selectById(senderId);
            String senderName = sender != null ? sender.getNickname() : "有人";
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("type", "MESSAGE");
            payloadMap.put("senderId", senderId);
            payloadMap.put("senderName", senderName);
            payloadMap.put("content", content != null ? content.substring(0, Math.min(content.length(), 50)) : "[图片]");
            String payload = objectMapper.writeValueAsString(payloadMap);
            webSocketBroadcaster.broadcast(new BroadcastMessage(receiverId, "MESSAGE", payload));
        } catch (Exception ignored) {}

        return toVO(msg);
    }

    public List<MessageVO> getConversation(Long userId, Long peerId, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<>();
        qw.and(w -> w
                .and(a -> a.eq(Message::getSenderId, userId).eq(Message::getReceiverId, peerId))
                .or(a -> a.eq(Message::getSenderId, peerId).eq(Message::getReceiverId, userId)));
        if (cursor != null) {
            qw.lt(Message::getId, cursor);
        }
        qw.orderByDesc(Message::getId);
        qw.last("LIMIT " + size);

        List<Message> list = messageMapper.selectList(qw);
        // 按时间正序显示
        Collections.reverse(list);
        return list.stream().map(this::toVO).toList();
    }

    /**
     * 获取所有对话列表，每条对话显示最后一条消息。
     * Bug fix 1.16: 使用 SQL 分组分页替代全量加载
     */
    public List<MessageVO> listConversations(Long userId) {
        List<Long> latestIds = messageMapper.selectLatestMessageIdsPerConversation(userId, 50);
        if (latestIds.isEmpty()) return List.of();
        List<Message> messages = messageMapper.selectBatchIds(latestIds);
        // 按 ID 倒序排列
        messages.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return messages.stream().map(this::toVO).toList();
    }

    @Transactional
    public void markRead(Long userId, Long peerId) {
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<>();
        qw.eq(Message::getReceiverId, userId)
          .eq(Message::getSenderId, peerId)
          .eq(Message::getIsRead, 0);
        List<Message> unread = messageMapper.selectList(qw);
        for (Message m : unread) {
            m.setIsRead(1);
            messageMapper.updateById(m);
        }
    }

    public long getUnreadCount(Long userId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverId, userId)
                .eq(Message::getIsRead, 0));
    }

    @Transactional
    public int markAllRead(Long userId) {
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<>();
        qw.eq(Message::getReceiverId, userId)
          .eq(Message::getIsRead, 0);
        List<Message> unread = messageMapper.selectList(qw);
        for (Message m : unread) {
            m.setIsRead(1);
            messageMapper.updateById(m);
        }
        return unread.size();
    }

    private MessageVO toVO(Message m) {
        User sender = userMapper.selectById(m.getSenderId());
        PublicUserVO senderVO = PublicUserVO.from(sender);
        // 同时返回接收者信息，用于对话列表正确显示对方头像和昵称
        User receiver = userMapper.selectById(m.getReceiverId());
        PublicUserVO receiverVO = PublicUserVO.from(receiver);
        return MessageVO.builder()
                .id(m.getId())
                .senderId(m.getSenderId())
                .receiverId(m.getReceiverId())
                .sender(senderVO)
                .receiver(receiverVO)
                .content(m.getContent())
                .imageUrl(m.getImageUrl())
                .isRead(m.getIsRead() != null && m.getIsRead() == 1)
                .createdAt(m.getCreatedAt())
                .build();
    }
}
