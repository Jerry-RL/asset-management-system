package com.ams.modules.asset;

/**
 * 租控状态机（SRS §4.24.1）：状态常量与合法迁移。
 * 状态仅由业务单据驱动，禁止手工直改。
 */
public final class LeaseControlStatus {

    private LeaseControlStatus() {
    }

    public static final String VACANT = "vacant";           // 空置
    public static final String LEASING = "leasing";         // 招租中
    public static final String LEASED = "leased";           // 在租
    public static final String PARTIAL_LEASED = "partial_leased"; // 部分出租
    public static final String SELF_USE = "self_use";       // 自用
    public static final String OCCUPIED = "occupied";       // 占用
    public static final String VACATING = "vacating";       // 退租中
    public static final String DISPOSING = "disposing";     // 处置中
    public static final String EXITED = "exited";           // 已退出

    /**
     * 校验迁移是否合法（DSD §4.3）。
     */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        return switch (from) {
            case VACANT -> switch (to) {
                case LEASING, SELF_USE, OCCUPIED, DISPOSING -> true;
                default -> false;
            };
            case LEASING -> to.equals(VACANT) || to.equals(LEASED);
            case LEASED -> to.equals(VACATING) || to.equals(PARTIAL_LEASED);
            case PARTIAL_LEASED -> to.equals(LEASED) || to.equals(VACANT);
            case SELF_USE -> to.equals(VACANT);
            case OCCUPIED -> to.equals(VACANT) || to.equals(LEASED);
            case VACATING -> to.equals(VACANT) || to.equals(LEASED);
            case DISPOSING -> to.equals(EXITED);
            default -> false;
        };
    }
}
