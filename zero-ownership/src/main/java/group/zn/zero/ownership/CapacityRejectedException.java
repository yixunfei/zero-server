package group.zn.zero.ownership;

/** 容量预算拒绝请求时抛出的确定性异常。 */
public final class CapacityRejectedException extends RuntimeException {
    /** 构造容量拒绝错误。 */
    public CapacityRejectedException(final String message) {
        super(message);
    }
}
