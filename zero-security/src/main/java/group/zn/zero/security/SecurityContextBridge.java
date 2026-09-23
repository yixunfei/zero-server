package group.zn.zero.security;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit, scoped security-context propagation boundary for transport adapters. */
public final class SecurityContextBridge {
    private static final ThreadLocal<SecurityContext> CURRENT = new ThreadLocal<>();

    private SecurityContextBridge() {
    }

    /** Returns the context explicitly bound to the current execution scope. */
    public static Optional<SecurityContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Executes a callback with a context and restores the previous scope afterwards. */
    public static <T> T with(final SecurityContext context, final Supplier<T> supplier) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(supplier, "supplier");
        SecurityContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    /** Executes a task with a context and restores the previous scope afterwards. */
    public static void with(final SecurityContext context, final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        with(context, () -> {
            runnable.run();
            return null;
        });
    }

    private static void restore(final SecurityContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
