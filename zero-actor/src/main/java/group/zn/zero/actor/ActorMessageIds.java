package group.zn.zero.actor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 内部关联 ID；进程/类加载器随机前缀加精确计数，不可用作安全令牌。 @author zn */
final class ActorMessageIds {
    /** 每次加载生成一次 128 位随机前缀；重启不复用。 */
    private static final String PREFIX = UUID.randomUUID() + "-";
    /** 一次性编码的 ASCII 前缀。 */
    private static final byte[] PREFIX_BYTES = PREFIX.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    /** 全线程共享序号，溢出后拒绝生成。 */
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private ActorMessageIds() { }
    /** 返回新的内部 ID；线程安全，修改全局计数，耗尽时抛出 IllegalStateException。 */
    static String next() {
        long sequence = SEQUENCE.incrementAndGet();
        if (sequence <= 0) throw new IllegalStateException("actor message ID sequence exhausted");
        byte[] value = new byte[PREFIX_BYTES.length + 13];
        int start = value.length;
        do {
            int digit = (int) (sequence % 36);
            value[--start] = (byte) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            sequence /= 36;
        } while (sequence != 0);
        start -= PREFIX_BYTES.length;
        System.arraycopy(PREFIX_BYTES, 0, value, start, PREFIX_BYTES.length);
        return new String(value, start, value.length - start, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
