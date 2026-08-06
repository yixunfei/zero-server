package group.zn.zero.starter.production;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * production starter 有界每 IP token-bucket 新连接限流器。
 *
 * <p>实现只保存不可逆 64 位地址指纹，不保存完整 IP；使用固定大小槽位避免攻击者制造无界 Map。
 * 指纹碰撞且旧槽仍活跃时采用保守拒绝，避免通过碰撞绕过限制。</p>
 *
 * @author zn
 */
final class ProductionIpConnectionRateLimiter implements NetworkRateLimiter {

    /** 槽位复用空闲时间。 */
    private static final long SLOT_REUSE_NANOS = Duration.ofMinutes(1).toNanos();
    /** 每秒纳秒数。 */
    private static final double NANOS_PER_SECOND = 1_000_000_000D;
    /** 每秒补充 token 数。 */
    private final int permitsPerSecond;
    /** token 突发容量。 */
    private final int burstCapacity;
    /** 固定大小槽位。 */
    private final Bucket[] buckets;
    /** 槽位掩码。 */
    private final int mask;
    /** 单调时钟。 */
    private final LongSupplier nanoTime;

    /**
     * 创建有界 IP 限流器。
     *
     * @param permitsPerSecond 每秒 token 数；必须大于 0。
     * @param burstCapacity 突发容量；必须大于 0 且不小于每秒 token 数。
     * @param slots 槽位数；必须是 16 到 1048576 之间的 2 次幂。
     */
    ProductionIpConnectionRateLimiter(
            final int permitsPerSecond,
            final int burstCapacity,
            final int slots) {
        this(permitsPerSecond, burstCapacity, slots, System::nanoTime);
    }

    ProductionIpConnectionRateLimiter(
            final int permitsPerSecond,
            final int burstCapacity,
            final int slots,
            final LongSupplier nanoTime) {
        if (permitsPerSecond < 1) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        if (burstCapacity < permitsPerSecond) {
            throw new IllegalArgumentException("burstCapacity must be at least permitsPerSecond");
        }
        if (slots < 16 || slots > 1_048_576 || Integer.bitCount(slots) != 1) {
            throw new IllegalArgumentException("slots must be a power of two between 16 and 1048576");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.burstCapacity = burstCapacity;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.buckets = new Bucket[slots];
        for (int index = 0; index < slots; index++) {
            buckets[index] = new Bucket();
        }
        this.mask = slots - 1;
    }

    /**
     * 尝试消耗远端 IP 的一个新连接 token。
     *
     * @param connection 新连接；不可为空。
     * @return true 表示允许进入握手；线程安全。
     */
    @Override
    public boolean allowConnection(final IConnection connection) {
        long fingerprint = fingerprint(Objects.requireNonNull(connection, "connection").remoteAddress());
        if (fingerprint == 0L) {
            return false;
        }
        int index = spread(fingerprint) & mask;
        return buckets[index].tryAcquire(
                fingerprint,
                nanoTime.getAsLong(),
                permitsPerSecond,
                burstCapacity);
    }

    private long fingerprint(final SocketAddress address) {
        if (!(address instanceof InetSocketAddress socketAddress) || socketAddress.getAddress() == null) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (byte value : socketAddress.getAddress().getAddress()) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    private int spread(final long value) {
        long mixed = value ^ value >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) (mixed ^ mixed >>> 32);
    }

    /**
     * 单个固定槽位 token bucket。
     */
    private static final class Bucket {

        /** 当前地址指纹。 */
        private long fingerprint;
        /** 当前 token。 */
        private double tokens;
        /** 最后补充时间。 */
        private long lastRefillNanos;
        /** 最后访问时间。 */
        private long lastSeenNanos;

        private synchronized boolean tryAcquire(
                final long currentFingerprint,
                final long now,
                final int permitsPerSecond,
                final int burstCapacity) {
            if (fingerprint != currentFingerprint) {
                if (fingerprint != 0L && now - lastSeenNanos < SLOT_REUSE_NANOS) {
                    return false;
                }
                fingerprint = currentFingerprint;
                tokens = burstCapacity;
                lastRefillNanos = now;
            }
            long elapsed = Math.max(0L, now - lastRefillNanos);
            tokens = Math.min(
                    burstCapacity,
                    tokens + elapsed * permitsPerSecond / NANOS_PER_SECOND);
            lastRefillNanos = now;
            lastSeenNanos = now;
            if (tokens < 1D) {
                return false;
            }
            tokens -= 1D;
            return true;
        }
    }
}
