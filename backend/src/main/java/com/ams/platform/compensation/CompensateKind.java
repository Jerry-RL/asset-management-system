package com.ams.platform.compensation;

/**
 * 补偿策略（{@code process_step.compensate_kind}）。
 *
 * <p>声明一个步骤失败后由谁负责收拾，是编排声明式约束的一部分：
 * <b>不允许既不声明策略、又带着外部副作用执行</b>（那会在失败时无人知晓）。
 *
 * <p>不使用枚举常量类而是枚举，是因为它参与编排签名（编译期强制声明），
 * 与 {@link ProcessStepStatus}（纯数据库取值）职责不同。
 */
public enum CompensateKind {

    /**
     * DB 内步骤：外层事务回滚即还原，无需人工动作。
     * 用于只改本库数据的步骤（退回核销、生成凭证记录）。
     */
    ROLLBACK("rollback"),

    /**
     * 外部副作用步骤：成功即在本库之外生效，<b>事务回滚救不回来</b>，失败必须交人工。
     * 用于调用第三方（电子发票红冲、支付渠道退款、ERP 推送）的步骤。
     */
    MANUAL("manual");

    private final String code;

    CompensateKind(String code) {
        this.code = code;
    }

    /** 落库取值。 */
    public String code() {
        return code;
    }
}
