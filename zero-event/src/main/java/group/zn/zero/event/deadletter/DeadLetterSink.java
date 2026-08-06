package group.zn.zero.event.deadletter;

/**
 * 事件死信接收器。
 *
 * @author zn
 */
@FunctionalInterface
public interface DeadLetterSink {

    /**
     * 记录死信事件。
     *
     * @param deadLetter 死信记录；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 写入失败时抛出，必须绑定 ErrorCode。
     */
    void record(DeadLetter deadLetter);
}
