package group.zn.zero.ownership;

/** 容量预算的只读运行时快照，便于应用接入指标或健康检查。 */
public record CapacitySnapshot(
        int maxConcurrent,
        int maxQueued,
        int active,
        int queued,
        boolean draining,
        boolean closed) {
    /** 校验计数关系。 */
    public CapacitySnapshot {
        if (maxConcurrent <= 0 || maxQueued < 0 || active < 0 || queued < 0
                || active > maxConcurrent || queued > maxQueued) {
            throw new IllegalArgumentException("capacity snapshot values are invalid");
        }
    }
}
