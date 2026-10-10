package group.zn.zero.net.kcp;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;

/** 算法接收段的有界元数据账本；仅 EventLoop 访问，不复制 payload，不反射算法内部队列。 @author zn */
final class KcpReceiveTracker {
    /** 两个算法接收队列各保留一个窗口。 */
    private final int capacity;
    /** 尚未 mergeRecv 的段；最多 capacity 项。 */
    private final Map<Integer, Segment> pending = new HashMap<>();
    /** 下一条业务消息起始段序号，按 32 位模数递增。 */
    private int expected;
    /** 可证明已经投递的历史跨度，最多半个序号空间减一。 */
    private long deliveredHistory;

    KcpReceiveTracker(final int receiveWindow) { capacity = Math.multiplyExact(2, receiveWindow); }

    /** 缓冲必须已通过 KcpSegments.valid；失败后调用方关闭会话，不再喂给算法。 */
    String accept(final ByteBuf body, final boolean quiescent) {
        int offset = body.readerIndex();
        while (offset < body.writerIndex()) {
            int length = body.getIntLE(offset + 20);
            if (body.getUnsignedByte(offset + 4) == 81) {
                String failure = accept(body.getIntLE(offset + 12), body.getUnsignedByte(offset + 5), length, quiescent);
                if (failure != null) return failure;
            }
            offset += 24 + length;
        }
        return null;
    }

    private String accept(final int sequence, final int fragment, final int length, final boolean quiescent) {
        long distance = Integer.toUnsignedLong(sequence - expected);
        if (distance >= 0x80000000L && Integer.toUnsignedLong(expected - sequence) <= deliveredHistory) return null;
        if (quiescent) return "receive.quiescent_push";
        if (distance >= capacity) return "receive.window_exceeded";
        Segment previous = pending.get(sequence);
        if (previous != null) {
            return previous.fragment() == fragment && previous.length() == length ? null : "receive.segment_conflict";
        }
        if (pending.size() >= capacity) return "receive.capacity_exceeded";
        pending.put(sequence, new Segment(fragment, length));
        return null;
    }

    /** 验证实际算法准备合并的消息；0 表示缺失/畸形分片，不能以字节数推测段数。 */
    int messageSegments(final int bytes) {
        Segment first = pending.get(expected);
        if (first == null) return 0;
        int count = first.fragment() + 1;
        long length = 0;
        for (int i = 0; i < count; i++) {
            Segment segment = pending.get(expected + i);
            if (segment == null || segment.fragment() != count - i - 1) return 0;
            length += segment.length();
        }
        return length == bytes ? count : 0;
    }

    /** 只有实际 mergeRecv 成功后调用；32 位回绕自然发生。 */
    void delivered(final int count) {
        for (int i = 0; i < count; i++) pending.remove(expected++);
        deliveredHistory = Math.min(0x7fffffffL, deliveredHistory + count);
    }

    boolean empty() { return pending.isEmpty(); }
    void clear() { pending.clear(); }

    /** 首次接收元数据，重复包不得覆盖。 @author zn */
    private record Segment(int fragment, int length) { }
}
