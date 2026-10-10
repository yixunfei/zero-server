package group.zn.zero.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 同一认证代际共享的单调到期状态；线程安全。 @author zn */
final class SecurityContextExpiry {
    /** 纳秒时间源，生产使用 System.nanoTime。 */
    private final LongSupplier ticker;
    /** 创建时单调刻度。 */
    private final long started;
    /** 初始剩余寿命上限。 */
    private final long budget;
    /** 一旦观察到到期即保持失效，防止墙钟回拨复活。 */
    private final AtomicBoolean expired = new AtomicBoolean();

    private SecurityContextExpiry(LongSupplier ticker, long started, long budget) {
        this.ticker = ticker;
        this.started = started;
        this.budget = budget;
    }

    static SecurityContextExpiry create(Instant expiresAt, Clock clock, LongSupplier ticker) {
        Objects.requireNonNull(ticker, "ticker");
        long started = ticker.getAsLong();
        Duration remaining = Duration.between(Objects.requireNonNull(clock, "clock").instant(),
                Objects.requireNonNull(expiresAt, "expiresAt"));
        long budget;
        if (remaining.isNegative() || remaining.isZero()) budget = 0;
        else {
            try { budget = remaining.toNanos(); }
            catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
        }
        return new SecurityContextExpiry(ticker, started, budget);
    }

    boolean expired(Instant expiresAt, Instant now) {
        long elapsed = ticker.getAsLong() - started;
        if (!expiresAt.isAfter(now) || elapsed >= budget) expired.set(true);
        return expired.get();
    }
}
