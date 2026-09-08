package com.ams.platform.integration.sms;

/**
 * 短信网关适配（FR-DUN-ESC / FR-NOTIF）。未配置时走 Mock 日志。
 */
public interface SmsAdapter {

    /** @return 第三方消息 ID 或 mock-id */
    String send(String phone, String content);
}
