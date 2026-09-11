package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Close waits for synchronous store operations before the adapter releases its connection resources. */
final class RepositoryScope {
    private boolean closed;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    <T> T access(final Supplier<T> action) {
        lock.readLock().lock();
        try {
            if (closed) {
                throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "repository source is closed", null);
            }
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    void close() {
        lock.writeLock().lock();
        try {
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
