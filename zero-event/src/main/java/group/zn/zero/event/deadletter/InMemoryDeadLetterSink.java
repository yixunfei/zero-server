package group.zn.zero.event.deadletter;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 内存死信接收器。
 *
 * @author zn
 */
public final class InMemoryDeadLetterSink implements DeadLetterSink {

    /**
     * 死信列表。
     */
    private final ArrayDeque<DeadLetter> deadLetters = new ArrayDeque<>();

    /** 最大保留条目数。 */
    private final int capacity;
    /** 已淘汰历史条目数。 */
    private long droppedCount;

    /** 创建最多保留 1024 条历史的本地接收器。 */
    public InMemoryDeadLetterSink() { this(1024); }

    /**
     * 创建有界历史接收器。
     * @param capacity 正容量。
     * @throws IllegalArgumentException 容量非正时抛出。
     */
    public InMemoryDeadLetterSink(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** @return 累计淘汰数量；线程安全，不改变数据。 */
    public synchronized long droppedCount() { return droppedCount; }

    /**
     * 记录死信事件。
     *
     * @param deadLetter 死信记录；不可为空。
     * @throws NullPointerException 当死信记录为空时抛出。
     */
    @Override
    public synchronized void record(final DeadLetter deadLetter) {
        java.util.Objects.requireNonNull(deadLetter, "deadLetter");
        if (deadLetters.size() == capacity) {
            deadLetters.removeFirst();
            droppedCount++;
        }
        deadLetters.addLast(deadLetter);
    }

    /**
     * 返回死信快照。
     *
     * @return 不可变死信快照；可能为空；按记录顺序排列；线程安全。
     */
    public synchronized List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }
}
