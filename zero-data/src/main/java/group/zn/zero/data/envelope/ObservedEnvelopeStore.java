package group.zn.zero.data.envelope;

import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Observes actual store operations while preserving backend batch and CAS semantics. */
public final class ObservedEnvelopeStore implements ZeroDataEnvelopeStore {
    /** Admission and failure boundary supplied by the runtime owner. */
    @FunctionalInterface
    public interface Observer {
        /** Executes once without transparent retry; write failures may have unknown outcomes. */
        <T> T call(boolean write, Supplier<T> operation);
    }

    private final ZeroDataEnvelopeStore delegate;
    private final Observer observer;

    /** Wraps a concrete store; the caller retains its lifecycle. */
    public ObservedEnvelopeStore(final ZeroDataEnvelopeStore delegate, final Observer observer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override public Optional<ZeroDataEnvelope> findById(final String id) {
        return observer.call(false, () -> delegate.findById(id));
    }

    @Override public List<ZeroDataEnvelope> findByIds(final Collection<String> ids) {
        return observer.call(false, () -> delegate.findByIds(ids));
    }

    @Override public List<ZeroDataEnvelope> findAll() {
        return observer.call(false, delegate::findAll);
    }

    @Override public PageResult<ZeroDataEnvelope> findPage(final PageRequest request) {
        return observer.call(false, () -> delegate.findPage(request));
    }

    @Override public void save(final ZeroDataEnvelope envelope) {
        observer.call(true, () -> { delegate.save(envelope); return null; });
    }

    @Override public boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        return observer.call(true, () -> delegate.saveIfVersion(envelope, expectedVersion));
    }

    @Override public List<Boolean> saveIfVersionBatch(final Collection<ConditionalWrite> writes) {
        return observer.call(true, () -> delegate.saveIfVersionBatch(writes));
    }

    @Override public void deleteById(final String id) {
        observer.call(true, () -> { delegate.deleteById(id); return null; });
    }

    @Override public void deleteAll(final Collection<String> ids) {
        observer.call(true, () -> { delegate.deleteAll(ids); return null; });
    }

    @Override public long count() {
        return observer.call(false, delegate::count);
    }
}
