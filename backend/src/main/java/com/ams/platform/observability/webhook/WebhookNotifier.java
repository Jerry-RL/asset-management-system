package com.ams.platform.observability.webhook;

/**
 * Webhook 出口（设计 §10.2）。
 *
 * <p>抽成接口的目的：告警发送必须<strong>可替换、可空实现</strong>。单测里注入一个记录型实现
 * 就能断言「发了什么、发了几次」，不必真的打网络；未配置告警时也有明确的空实现，
 * 而不是在每个调用点写 {@code if (enabled)}。
 */
public interface WebhookNotifier {

    /**
     * 发送一条告警。
     *
     * <p><strong>实现必须自己吞掉所有异常</strong>：告警只是通知，发送失败绝不能影响业务，
     * 也不该让调用方（ingest 链路）感知。
     *
     * @param title  标题（企微/钉钉 markdown 用）
     * @param body   正文（markdown 文本；generic 型会用结构化 JSON）
     * @param payload 结构化字段（generic 型用；wecom/dingtalk 忽略）
     */
    void notify(String title, String body, WebhookPayload payload);
}
