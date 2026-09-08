package com.ams.platform.auth;

import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token 吊销黑名单（NFR-SEC-006）。
 */
@Service
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "auth:token:blacklist:";

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenBlacklistService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String jti, long remainMillis) {
        if (remainMillis > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + jti, "1", remainMillis, TimeUnit.MILLISECONDS);
        }
    }

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }
}
