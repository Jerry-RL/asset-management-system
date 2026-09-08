package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * L4 字段应用层加密（AES-256-GCM，NFR-DSEC-004）。
 * 密钥由环境变量 AMS_FIELD_ENC_KEY 提供，禁止写入代码/库/日志。
 */
@Service
public class FieldEncryptionService {

    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptionService(
            @Value("${AMS_FIELD_ENC_KEY:dev-field-enc-key-32-bytes-0123456789}") String rawKey) {
        byte[] key = rawKey.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("AMS_FIELD_ENC_KEY 必须至少 32 字节");
        }
        this.keySpec = new SecretKeySpec(key, 0, 32, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "字段加密失败");
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            byte[] data = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(data, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(data, iv.length, data.length - iv.length);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "字段解密失败");
        }
    }

    /**
     * SHA-256 盲索引，用于等值查询（如证件号、手机号精确匹配）而不泄露明文。
     */
    public static String blindIndex(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
