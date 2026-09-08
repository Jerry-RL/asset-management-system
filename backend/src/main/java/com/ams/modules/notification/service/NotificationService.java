package com.ams.modules.notification.service;

import com.ams.modules.notification.entity.Notification;
import com.ams.modules.notification.mapper.NotificationMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 消息通知（FR-NOTIF-*）：站内信发送、未读计数、已读。
 * 多渠道（短信/小程序订阅消息）适配器预留。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public Notification send(Long userId, String title, String content, String bizType,
            Long bizId, String channel) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        notification.setChannel(channel == null ? "in_app" : channel);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        return notification;
    }

    public List<Notification> list(boolean unreadOnly) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(unreadOnly, Notification::getReadAt, null)
                        .orderByDesc(Notification::getId));
    }

    public long unreadCount() {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .isNull(Notification::getReadAt));
    }

    public void markRead(Long notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification != null && notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(notification);
        }
    }
}
