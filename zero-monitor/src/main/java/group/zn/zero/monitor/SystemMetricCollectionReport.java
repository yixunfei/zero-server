package group.zn.zero.monitor;

import java.util.List;
import java.util.Objects;

/**
 * 单次系统指标采集报告。
 *
 * @param attemptedProbeCount 已尝试探针数量。
 * @param successfulProbeCount 成功探针数量。
 * @param failures 失败探针的安全摘要；不可变、有序、可能为空、线程安全。
 * @author zn
 */
public record SystemMetricCollectionReport(
        int attemptedProbeCount,
        int successfulProbeCount,
        List<ProbeFailure> failures) {

    /**
     * 创建不可变采集报告。
     *
     * @throws NullPointerException 当失败列表或其中元素为空时抛出。
     * @throws IllegalArgumentException 当探针计数与失败数量不一致时抛出。
     */
    public SystemMetricCollectionReport {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (attemptedProbeCount < 0
                || successfulProbeCount < 0
                || successfulProbeCount > attemptedProbeCount
                || attemptedProbeCount - successfulProbeCount != failures.size()) {
            throw new IllegalArgumentException("probe counts do not match failure count");
        }
    }

    /**
     * 判断全部探针是否成功。
     *
     * @return {@code true} 表示没有探针失败；不修改报告；线程安全。
     */
    public boolean successful() {
        return failures.isEmpty();
    }

    /**
     * 受控系统指标探针。
     *
     * @author zn
     */
    public enum Probe {

        /**
         * JVM 内存与类加载探针。
         */
        MEMORY,

        /**
         * JVM 线程探针。
         */
        THREAD,

        /**
         * JVM GC 探针。
         */
        GARBAGE_COLLECTION,

        /**
         * 进程与系统 CPU 探针。
         */
        CPU,

        /**
         * 磁盘空间探针。
         */
        DISK,

        /**
         * 网络接口探针。
         */
        NETWORK
    }

    /**
     * 单个探针失败的安全摘要。
     *
     * <p>该值只保存受控探针枚举和异常类型名，不保留可变 {@link Throwable}、原异常消息、
     * 文件路径、网络地址或其他运行时原值。
     *
     * @param probe 失败探针。
     * @param causeType 原始异常的类型名，不含异常消息。
     * @author zn
     */
    public record ProbeFailure(Probe probe, String causeType) {

        /**
         * 创建安全失败摘要。
         *
         * @throws NullPointerException 当探针或异常类型名为空时抛出。
         * @throws IllegalArgumentException 当异常类型名为空白时抛出。
         */
        public ProbeFailure {
            probe = Objects.requireNonNull(probe, "probe");
            causeType = Objects.requireNonNull(causeType, "causeType");
            if (causeType.isBlank()) {
                throw new IllegalArgumentException("causeType must not be blank");
            }
        }

        /**
         * 从异常构造不保留原对象和消息的失败摘要。
         *
         * @param probe 失败探针；不可为空。
         * @param cause 原始异常；不可为空；方法返回后不会被持有。
         * @return 安全且不可变的失败摘要；不可为空；线程安全。
         * @throws NullPointerException 当探针或异常为空时抛出。
         */
        public static ProbeFailure from(final Probe probe, final Throwable cause) {
            Throwable current = Objects.requireNonNull(cause, "cause");
            return new ProbeFailure(probe, current.getClass().getName());
        }

        /**
         * 返回该失败绑定的稳定错误码。
         *
         * @return 固定为 {@link MonitorErrorCode#SYSTEM_PROBE_FAILED}；不可为空；线程安全。
         */
        public MonitorErrorCode errorCode() {
            return MonitorErrorCode.SYSTEM_PROBE_FAILED;
        }
    }
}
