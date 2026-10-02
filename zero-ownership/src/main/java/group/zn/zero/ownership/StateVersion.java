package group.zn.zero.ownership;

/** 权威状态版本；提交端使用它执行乐观并发控制。 */
public record StateVersion(long value) {
    /** 校验版本值。 */
    public StateVersion {
        if (value < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
    }

    /** 返回初始状态版本。 */
    public static StateVersion initial() {
        return new StateVersion(0);
    }

    /** 返回下一个版本。 */
    public StateVersion next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("state version exhausted");
        }
        return new StateVersion(value + 1);
    }
}
