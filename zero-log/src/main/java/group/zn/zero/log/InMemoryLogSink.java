package group.zn.zero.log;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存日志 sink。
 *
 * <p>用于本地原型、测试和 smoke flow。内部使用同步列表保存日志，不适合作为生产高吞吐落地实现。
 *
 * @author zn
 */
public final class InMemoryLogSink implements LogSink {

    /**
     * 日志记录列表。
     */
    private Queue<ZeroLogRecord> recordQueue = new LinkedList<>();

    /**
     * 日志列表读写锁。
     */
    private final ReentrantReadWriteLock recordLock = new ReentrantReadWriteLock();

    /**
     * 日志列表读锁。
     */
    private final Lock readLock = recordLock.readLock();

    /**
     * 日志列表写锁。
     */
    private final Lock writeLock = recordLock.writeLock();

    /**
     * 写入日志记录。
     *
     * @param record 日志记录；不可为空。
     * @throws NullPointerException 当日志记录为空时抛出。
     */
    @Override
    public void append(final ZeroLogRecord record) {
        writeLock.lock();
        try {
            recordQueue.add(Objects.requireNonNull(record, "record"));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 返回日志记录快照。
     *
     * @return 不可变、有序、可能为空、线程安全的日志记录快照。
     */
    public List<ZeroLogRecord> records() {
        readLock.lock();
        try {
            return List.copyOf(recordQueue);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 清空内存日志。
     *
     * <p>该方法会修改 sink 内部状态，线程安全。
     */
    public void clear() {
        writeLock.lock();
        try {
            recordQueue = new LinkedList<>();
        } finally {
            writeLock.unlock();
        }
    }
}
