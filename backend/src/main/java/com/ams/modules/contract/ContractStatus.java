package com.ams.modules.contract;

/**
 * 合同状态机（SRS §4.24.9）：九态。
 */
public final class ContractStatus {

    private ContractStatus() {
    }

    public static final String DRAFT = "draft";
    public static final String APPROVING = "approving";
    public static final String ACTIVE = "active";
    public static final String EXPIRING = "expiring";
    public static final String RENEWABLE = "renewable";
    public static final String EXPIRED = "expired";
    public static final String TERMINATING = "terminating";
    public static final String TERMINATED = "terminated";
    public static final String VOIDED = "voided";

    public static boolean canTransition(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        return switch (from) {
            case DRAFT -> to.equals(APPROVING) || to.equals(VOIDED);
            case APPROVING -> to.equals(ACTIVE) || to.equals(DRAFT); // 驳回回草稿
            case ACTIVE -> to.equals(EXPIRING) || to.equals(RENEWABLE)
                    || to.equals(EXPIRED) || to.equals(TERMINATING) || to.equals(TERMINATED);
            case EXPIRING -> to.equals(RENEWABLE) || to.equals(EXPIRED) || to.equals(ACTIVE);
            case RENEWABLE -> to.equals(ACTIVE) || to.equals(EXPIRED);
            case EXPIRED -> to.equals(TERMINATED) || to.equals(RENEWABLE);
            case TERMINATING -> to.equals(TERMINATED) || to.equals(ACTIVE); // 回滚
            default -> false;
        };
    }
}
