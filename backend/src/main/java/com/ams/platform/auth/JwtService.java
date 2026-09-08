package com.ams.platform.auth;

import com.ams.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 服务：Access 2h + Refresh 7d（NFR-SEC-001），jti 支持吊销。
 */
@Service
public class JwtService {

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String createAccessToken(Long userId, String username, String clientType) {
        return build(userId, username, clientType,
                Duration.ofMinutes(properties.accessExpirationMinutes()), accessKey());
    }

    public String createRefreshToken(Long userId, String username, String clientType) {
        return build(userId, username, clientType,
                Duration.ofDays(properties.refreshExpirationDays()), refreshKey());
    }

    public Claims parseAccessToken(String token) {
        return parse(token, accessKey());
    }

    public Claims parseRefreshToken(String token) {
        return parse(token, refreshKey());
    }

    private String build(Long userId, String username, String clientType, Duration ttl, SecretKey key) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("clientType", clientType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    private Claims parse(String token, SecretKey key) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    private SecretKey accessKey() {
        return Keys.hmacShaKeyFor(properties.accessSecret().getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey refreshKey() {
        return Keys.hmacShaKeyFor(properties.refreshSecret().getBytes(StandardCharsets.UTF_8));
    }
}
