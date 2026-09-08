package com.ams.modules.billing;

/**
 * 账单状态机（SRS §4.24.2）。
 */
public final class BillStatus {

    private BillStatus() {
    }

    public static final String PENDING_ISSUE = "pending_issue"; // 待出账
    public static final String UNPAID = "unpaid";               // 待缴
    public static final String PARTIAL_PAID = "partial_paid";   // 部分缴
    public static final String PAID = "paid";                   // 已缴
    public static final String REDUCED = "reduced";             // 已减免
    public static final String VOIDED = "voided";               // 已作废
}
