package com.ams.platform.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 错误指纹（设计 §4.2、§7.3）。
 *
 * <p><strong>必须由服务端计算</strong>，不接受客户端上报：指纹是告警冷却与 Top 错误聚合的
 * 键，若可被客户端指定，伪造一个每次不同的指纹就能绕过冷却、把告警刷爆。
 *
 * <p>输入取 {@code source | appType | message | stackHead} 四段，理由：
 * <ul>
 *   <li>含 {@code source} / {@code appType}：同一个报错在「小程序」与「H5」上是两个问题
 *       （运行环境不同，修复方式不同），不应合并成一个指纹；</li>
 *   <li>含 {@code message}：区分不同错误；</li>
 *   <li>只取 stack <strong>前 200 字符</strong>：栈尾通常含行号/列号/内存地址等每次都变的
 *       内容，全量入参会把同一个错误算成无数个指纹，冷却与聚合双双失效。取栈头（错误类型
 *       + 首个业务帧）既稳定又足以区分。</li>
 * </ul>
 */
public final class AppLogFingerprint {

    private AppLogFingerprint() {
    }

    /** stack 参与指纹的最大长度。见类注释：超出的部分每次都变，纳入会让指纹失去稳定性。 */
    public static final int STACK_HEAD_LENGTH = 200;

    /** 输出取 sha-256 前 32 个 hex 字符（128 bit，碰撞概率对日志聚合可忽略）。 */
    private static final int OUTPUT_LENGTH = 32;

    /**
     * 计算指纹。
     *
     * @param source  来源（{@code js} / {@code promise} / {@code api} / {@code backend}）
     * @param appType 来源端
     * @param message 错误信息
     * @param stack   堆栈（可为 null；只取前 {@value #STACK_HEAD_LENGTH} 字符）
     */
    public static String compute(String source, String appType, String message, String stack) {
        String canonical = nullToEmpty(source)
                + "\u0001" + nullToEmpty(appType)
                + "\u0001" + nullToEmpty(message)
                + "\u0001" + head(nullToEmpty(stack), STACK_HEAD_LENGTH);
        return sha256Hex(canonical).substring(0, OUTPUT_LENGTH);
    }

    private static String head(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明运行环境已损坏，直接暴露而非静默降级
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
