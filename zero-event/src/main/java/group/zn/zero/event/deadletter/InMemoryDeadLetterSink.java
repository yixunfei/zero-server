package group.zn.zero.event.deadletter;

import java.util.ArrayList;
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
    private final List<DeadLetter> deadLetters = new ArrayList<>();

    /**
     * 记录死信事件。
     *
     * @param deadLetter 死信记录；不可为空。
     * @throws NullPointerException 当死信记录为空时抛出。
     */
    @Override
    public synchronized void record(final DeadLetter deadLetter) {
        deadLetters.add(java.util.Objects.requireNonNull(deadLetter, "deadLetter"));
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
