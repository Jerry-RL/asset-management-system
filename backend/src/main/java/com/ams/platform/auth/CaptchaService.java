package com.ams.platform.auth;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 图形验证码（FR-COM-001）：Redis 存储 captcha:{id}，TTL 5min。
 */
@Service
public class CaptchaService {

    private static final long TTL_SECONDS = 5 * 60;
    private static final String PREFIX = "captcha:";
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisTemplate<String, Object> redisTemplate;

    public CaptchaService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Map<String, String> generate() {
        String code = randomCode(4);
        String id = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + id, code, TTL_SECONDS, TimeUnit.SECONDS);
        return Map.of("captchaId", id, "captchaImage", "data:image/png;base64," + render(code));
    }

    public void verify(String captchaId, String captchaCode) {
        if (captchaId == null || captchaCode == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "验证码不能为空");
        }
        String key = PREFIX + captchaId;
        Object stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "验证码已过期");
        }
        redisTemplate.delete(key);
        if (!stored.toString().equalsIgnoreCase(captchaCode.trim())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "验证码错误");
        }
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String render(String code) {
        int width = 120;
        int height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, width, height);
            g.setFont(new Font("Arial", Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(40 + RANDOM.nextInt(120), 40 + RANDOM.nextInt(120),
                        40 + RANDOM.nextInt(120)));
                g.drawString(String.valueOf(code.charAt(i)), 18 + i * 24, 29);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        } finally {
            g.dispose();
        }
    }
}
