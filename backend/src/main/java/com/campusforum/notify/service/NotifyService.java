package com.campusforum.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.notify.domain.Notification;
import com.campusforum.notify.dto.NotificationVO;
import com.campusforum.notify.mapper.NotificationMapper;
import com.campusforum.infra.websocket.BroadcastMessage;
import com.campusforum.infra.websocket.WebSocketBroadcaster;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final WebSocketBroadcaster webSocketBroadcaster;

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    @Transactional
    public void create(Long receiverId, Long senderId, String type, String title, String content, String redirectUrl) {
        if (receiverId.equals(senderId)) return;

        // 检查免打扰设置
        User receiver = userMapper.selectById(receiverId);
        if (receiver != null && receiver.getMuteSettings() != null) {
            try {
                Set<String> muted = jsonMapper.readValue(receiver.getMuteSettings(),
                        new TypeReference<Set<String>>() {});
                if (muted.contains(type)) {
                    log.debug("Notification muted: type={}, receiver={}", type, receiverId);
                    return;
                }
            } catch (JsonProcessingException ignored) {}
        }

        Notification notif = new Notification();
        notif.setReceiverId(receiverId);
        notif.setSenderId(senderId);
        notif.setType(type);
        notif.setTitle(title);
        notif.setContent(content);
        notif.setRedirectUrl(redirectUrl);
        notif.setIsRead(0);

        notificationMapper.insert(notif);
        log.debug("Notification created: type={}, receiver={}", type, receiverId);

        // Bug fix 1.13: 使用 ObjectMapper 安全构造 JSON，防止注入
        try {
            Map<String, Object> payloadMap = new java.util.LinkedHashMap<>();
            payloadMap.put("type", type != null ? type : "");
            payloadMap.put("title", title != null ? title : "");
            payloadMap.put("content", content != null ? content : "");
            String payload = jsonMapper.writeValueAsString(payloadMap);
            webSocketBroadcaster.broadcast(new BroadcastMessage(receiverId, type, payload));
        } catch (Exception ignored) {
            // WebSocket push is best-effort
        }
    }

    public List<NotificationVO> list(Long userId, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<Notification> qw = new LambdaQueryWrapper<>();
        qw.eq(Notification::getReceiverId, userId);
        if (cursor != null) {
            qw.lt(Notification::getId, cursor);
        }
        qw.orderByDesc(Notification::getId);
        qw.last("LIMIT " + size);

        return notificationMapper.selectList(qw).stream().map(this::toVO).toList();
    }

    public long getUnreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, userId)
                .eq(Notification::getIsRead, 0));
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        Notification notif = notificationMapper.selectById(notificationId);
        if (notif == null || !notif.getReceiverId().equals(userId)) return;
        notif.setIsRead(1);
        notificationMapper.updateById(notif);
    }

    @Transactional
    public void markAllRead(Long userId) {
        List<Notification> unread = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, userId)
                .eq(Notification::getIsRead, 0));
        for (Notification n : unread) {
            n.setIsRead(1);
            notificationMapper.updateById(n);
        }
    }

    @Transactional
    public int batchMarkRead(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) return 0;
        int count = 0;
        for (Long id : ids) {
            Notification notif = notificationMapper.selectById(id);
            if (notif == null || !notif.getReceiverId().equals(userId)) continue;
            if (notif.getIsRead() == 1) continue; // 已读跳过
            notif.setIsRead(1);
            notificationMapper.updateById(notif);
            count++;
        }
        return count;
    }

    private NotificationVO toVO(Notification n) {
        PublicUserVO senderVO = null;
        if (n.getSenderId() != null) {
            User sender = userMapper.selectById(n.getSenderId());
            senderVO = PublicUserVO.from(sender);
        }

        return NotificationVO.builder()
                .id(n.getId())
                .receiverId(n.getReceiverId())
                .senderId(n.getSenderId())
                .sender(senderVO)
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .redirectUrl(n.getRedirectUrl())
                .isRead(n.getIsRead() == 1)
                .createdAt(n.getCreatedAt())
                .build();
    }
}
