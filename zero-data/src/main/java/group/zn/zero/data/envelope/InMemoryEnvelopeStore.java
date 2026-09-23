package group.zn.zero.data.envelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Local envelope storage with the same optimistic-version contract as database stores. */
public final class InMemoryEnvelopeStore implements ZeroDataEnvelopeStore {
    private final Map<String, ZeroDataEnvelope> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<ZeroDataEnvelope> findById(final String id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "id")));
    }

    @Override
    public synchronized List<ZeroDataEnvelope> findAll() {
        return List.copyOf(entries.values());
    }

    @Override
    public synchronized void save(final ZeroDataEnvelope envelope) {
        entries.put(envelope.id(), envelope);
    }

    @Override
    public synchronized boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        ZeroDataEnvelope previous = entries.get(envelope.id());
        if (expectedVersion == 0 ? previous != null : previous == null || previous.version() != expectedVersion) {
            return false;
        }
        save(envelope);
        return true;
    }

    @Override
    public synchronized void deleteById(final String id) {
        entries.remove(Objects.requireNonNull(id, "id"));
    }

    @Override
    public synchronized long count() {
        return entries.size();
    }
}
