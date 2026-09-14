package com.ams.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指纹计算测试（设计 §4.2、§7.3）。
 *
 * <p>指纹是告警冷却与 Top 错误聚合的键，因此本测试守着两条相反方向的特性：
 * <ul>
 *   <li><b>稳定性</b>：同一个错误必须产出同一个指纹 —— 否则冷却失效（每次都是新指纹，
 *       告警每次都发），Top 聚合也会被拆成无数条；</li>
 *   <li><b>区分度</b>：不同错误必须产出不同指纹 —— 否则不同的缺陷被合成一个指纹，
 *       冷却会把真正的新问题一起压掉。</li>
 * </ul>
 */
class AppLogFingerprintTest {

    private static final String SOURCE = "js";
    private static final String APP = "h5-tenant";
    private static final String MESSAGE = "TypeError: Cannot read property 'id' of undefined";
    private static final String STACK = "at BillPage (bill.js:10:5)\nat render (react.js:1:1)";

    @Test
    @DisplayName("同输入同指纹（稳定性）")
    void stableForSameInput() {
        String first = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, STACK);
        String second = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, STACK);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("输出为 32 位小写 hex（与库字段长度一致）")
    void isHex32() {
        String fingerprint = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, STACK);
        assertThat(fingerprint).hasSize(32).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("任一段不同则指纹不同（区分度）")
    void differsWhenAnyPartDiffers() {
        String base = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, STACK);

        assertThat(AppLogFingerprint.compute("promise", APP, MESSAGE, STACK)).isNotEqualTo(base);
        // 同一个报错在小程序与 H5 上是两个问题（运行环境不同、修复方式不同），不应合并
        assertThat(AppLogFingerprint.compute(SOURCE, "tenant-mp", MESSAGE, STACK)).isNotEqualTo(base);
        assertThat(AppLogFingerprint.compute(SOURCE, APP, MESSAGE + "2", STACK)).isNotEqualTo(base);
        assertThat(AppLogFingerprint.compute(SOURCE, APP, MESSAGE, "at other.js:1:1")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("堆栈只取前 200 字符：栈尾的易变内容不影响指纹")
    void ignoresStackTailBeyondHead() {
        String stableHead = "at BillPage (bill.js:10:5)";
        String tailA = "\n" + "x".repeat(300) + "aaa";
        String tailB = "\n" + "x".repeat(300) + "bbb";

        String first = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, stableHead + tailA);
        String second = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, stableHead + tailB);

        // 这是本设计最容易被破坏的一条：把完整堆栈纳入指纹，同一个错误会因为栈尾
        // 的行号/地址变化被判成新错误，冷却形同虚设。
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("栈头不同则指纹不同（区分度不因截断而丢失）")
    void distinguishesByStackHead() {
        String first = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, "at BillPage (bill.js:10:5)");
        String second = AppLogFingerprint.compute(SOURCE, APP, MESSAGE, "at PayPage (pay.js:7:3)");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("null 段不抛异常，且与空串等价")
    void toleratesNulls() {
        String withNull = AppLogFingerprint.compute(null, null, null, null);
        String withEmpty = AppLogFingerprint.compute("", "", "", "");
        assertThat(withNull).isEqualTo(withEmpty).hasSize(32);
    }

    @Test
    @DisplayName("分隔符防碰撞：拼接歧义的两组输入不应得到同一指纹")
    void separatorPreventsAmbiguity() {
        // 若用普通字符串拼接，"ab"+"c" 与 "a"+"bc" 会撞成同一个指纹
        String first = AppLogFingerprint.compute("ab", "c", "m", "s");
        String second = AppLogFingerprint.compute("a", "bc", "m", "s");
        assertThat(first).isNotEqualTo(second);
    }
}
