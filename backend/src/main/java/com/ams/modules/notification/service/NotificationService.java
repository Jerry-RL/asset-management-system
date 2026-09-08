package com.ams.modules.notification.service;

import com.ams.modules.notification.entity.Notification;
import com.ams.modules.notification.entity.NotificationTemplate;
import com.ams.modules.notification.mapper.NotificationMapper;
import com.ams.modules.notification.mapper.NotificationTemplateMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 消息通知（FR-NOTIF-*）：模板变量渲染、站内信、未读计数。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationTemplateMapper templateMapper;

    public NotificationService(
            NotificationMapper notificationMapper, NotificationTemplateMapper templateMapper) {
        this.notificationMapper = notificationMapper;
        this.templateMapper = templateMapper;
    }

    public Notification send(Long userId, String title, String content, String bizType,
            Long bizId, String channel) {
        if (userId == null) {
            return null;
        }
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

    /** 按模板发送（FR-NOTIF-003），变量 {{key}} 替换。 */
    public Notification sendByTemplate(String templateCode, Long userId, Map<String, String> vars,
            String bizType, Long bizId) {
        NotificationTemplate tpl = templateMapper.selectOne(
                new LambdaQueryWrapper<NotificationTemplate>()
                        .eq(NotificationTemplate::getCode, templateCode)
                        .eq(NotificationTemplate::getEnabled, true)
                        .last("LIMIT 1"));
        String title;
        String body;
        String channel = "in_app";
        if (tpl == null) {
            title = templateCode;
            body = vars == null ? "" : vars.toString();
        } else {
            title = render(tpl.getTitleTpl(), vars);
            body = render(tpl.getBodyTpl(), vars);
            channel = tpl.getChannel() == null ? "in_app" : tpl.getChannel();
        }
        return send(userId, title, body, bizType, bizId, channel);
    }

    public List<NotificationTemplate> listTemplates() {
        return templateMapper.selectList(
                new LambdaQueryWrapper<NotificationTemplate>().orderByAsc(NotificationTemplate::getId));
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

    private static String render(String tpl, Map<String, String> vars) {
        if (tpl == null) {
            return "";
        }
        String out = tpl;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }
}
