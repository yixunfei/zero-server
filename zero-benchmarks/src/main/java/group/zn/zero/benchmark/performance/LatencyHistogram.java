package group.zn.zero.benchmark.performance;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** 固定 8192 桶的对数直方图；返回桶上界，非负纳秒值的相对量化误差小于 1%。 @author zn */
final class LatencyHistogram {
    /** 128 个尾数桶乘 64 个二进制指数。 */
    private final AtomicLongArray buckets = new AtomicLongArray(8192);
    /** 精确样本数。 */
    private final AtomicLong count = new AtomicLong();
    /** 精确最大值。 */
    private final AtomicLong maximum = new AtomicLong();
    void record(final long nanos) {
        long value = Math.max(1, nanos);
        int exponent = 63 - Long.numberOfLeadingZeros(value);
        int shift = Math.max(0, exponent - 7);
        int mantissa = (int) (value >>> shift) & 127;
        buckets.incrementAndGet(exponent * 128 + mantissa);
        maximum.accumulateAndGet(value, Math::max);
        count.incrementAndGet();
    }
    long percentile(final double quantile) {
        long target = (long) Math.ceil(count.get() * quantile);
        if (target == 0) return 0;
        long cumulative = 0;
        for (int i = 0; i < buckets.length(); i++) {
            cumulative += buckets.get(i);
            if (cumulative >= target) {
                int exponent = i / 128;
                int mantissa = i % 128;
                if (exponent < 7) return mantissa;
                int shift = exponent - 7;
                long lower = (128L + mantissa) << shift;
                return Math.min(maximum.get(), lower + (1L << shift) - 1);
            }
        }
        return maximum.get();
    }
    long maximum() { return maximum.get(); }
}
