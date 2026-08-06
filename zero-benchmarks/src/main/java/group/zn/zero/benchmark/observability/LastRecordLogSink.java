package group.zn.zero.benchmark.observability;

import group.zn.zero.log.LogSink;
import group.zn.zero.log.ZeroLogRecord;
import java.util.Objects;

/**
 * 仅保留最后一条日志记录的 benchmark 有界终端。
 *
 * <p>实例专用于 {@code Scope.Thread} 的 JMH 状态，不提供跨线程可见性保证，不执行 IO，
 * 也不创建线程。每次写入都会覆盖旧引用，因此测量期间的终端状态始终有界。
 *
 * @author zn
 */
final class LastRecordLogSink implements LogSink {

    /**
     * 最后一条日志记录；尚未写入或清理后为空。
     */
    private ZeroLogRecord lastRecord;

    /**
     * 保存最后一条日志记录并覆盖旧引用。
     *
     * <p>该方法只修改当前 benchmark 线程私有状态，不执行外部 IO。
     *
     * @param record 日志记录；不可为空。
     * @throws NullPointerException 日志记录为空时抛出。
     */
    @Override
    public void append(final ZeroLogRecord record) {
        lastRecord = Objects.requireNonNull(record, "record");
    }

    /**
     * 返回最后写入的日志记录。
     *
     * @return 不可变、非空、线程安全的日志记录。
     * @throws NullPointerException 尚未写入或已经清理时抛出。
     */
    ZeroLogRecord lastRecord() {
        return Objects.requireNonNull(lastRecord, "lastRecord");
    }

    /**
     * 清理最后一条记录引用。
     *
     * <p>该方法只修改当前 benchmark 线程私有状态，不修改日志记录本身。
     */
    void clear() {
        lastRecord = null;
    }
}
