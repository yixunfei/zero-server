package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Keeps retained repositories inside the lifetime and error boundary of their owning adapter. */
final class ScopedEnvelopeStore implements ZeroDataEnvelopeStore {
    private final RepositoryScope scope;
    private final ZeroDataEnvelopeStore delegate;

    ScopedEnvelopeStore(final RepositoryScope scope, final ZeroDataEnvelopeStore delegate) {
        this.scope = scope;
        this.delegate = delegate;
    }

    @Override
    public Optional<ZeroDataEnvelope> findById(final String id) {
        return access(() -> delegate.findById(id), DataErrorCode.READ_FAILED);
    }

    @Override
    public List<ZeroDataEnvelope> findAll() {
        return access(delegate::findAll, DataErrorCode.READ_FAILED);
    }

    @Override
    public void save(final ZeroDataEnvelope envelope) {
        access(() -> { delegate.save(envelope); return null; }, DataErrorCode.WRITE_FAILED);
    }

    @Override
    public boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        return access(() -> delegate.saveIfVersion(envelope, expectedVersion), DataErrorCode.WRITE_FAILED);
    }

    @Override
    public void deleteById(final String id) {
        access(() -> { delegate.deleteById(id); return null; }, DataErrorCode.DELETE_FAILED);
    }

    @Override
    public long count() {
        return access(delegate::count, DataErrorCode.READ_FAILED);
    }

    private <T> T access(final Supplier<T> action, final DataErrorCode fallback) {
        return scope.access(() -> {
            try {
                return action.get();
            } catch (RuntimeException failure) {
                DataErrorCode code = failure instanceof ZeroException zero && zero.errorCode() instanceof DataErrorCode data
                        ? data : fallback;
                throw ZeroException.of(code, code.message(), null);
            }
        });
    }
}
