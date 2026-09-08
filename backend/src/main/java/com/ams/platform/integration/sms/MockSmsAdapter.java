package com.ams.platform.integration.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockSmsAdapter implements SmsAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockSmsAdapter.class);

    @Override
    public String send(String phone, String content) {
        String id = "mock_sms_" + System.currentTimeMillis();
        log.info("SMS mock send phone={} content={} id={}", phone, content, id);
        return id;
    }
}
