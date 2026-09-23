package group.zn.zero.gm;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded in-memory idempotency reference implementation for local/tests. */
public final class InMemoryGmIdempotencyStore implements GmIdempotencyStore {
    private final Map<String, Entry> entries = new HashMap<>();

    @Override
    public synchronized Claim claim(
            final String key,
            final String fingerprint,
            final Instant now,
            final Duration ttl) {
        require(key, "key", 256);
        require(fingerprint, "fingerprint", 256);
        Objects.requireNonNull(now, "now");
        positive(ttl, "ttl");
        Entry existing = entries.get(key);
        if (existing != null && !existing.expiresAt().isAfter(now)) {
            entries.remove(key);
            existing = null;
        }
        if (existing == null) {
            entries.put(key, new Entry(fingerprint, State.IN_PROGRESS, null, now.plus(ttl)));
            return new Claim(State.CLAIMED, null);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Claim(State.CONFLICT, null);
        }
        return new Claim(existing.state(), existing.response());
    }

    @Override
    public synchronized void complete(
            final String key,
            final String fingerprint,
            final GmOperationResponse response,
            final Instant expiresAt) {
        update(key, fingerprint, State.COMPLETED, Objects.requireNonNull(response, "response"), expiresAt);
    }

    @Override
    public synchronized void fail(
            final String key,
            final String fingerprint,
            final Instant expiresAt) {
        update(key, fingerprint, State.FAILED, null, expiresAt);
    }

    public synchronized int size() {
        return entries.size();
    }

    private void update(
            final String key,
            final String fingerprint,
            final State state,
            final GmOperationResponse response,
            final Instant expiresAt) {
        require(key, "key", 256);
        require(fingerprint, "fingerprint", 256);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Entry existing = entries.get(key);
        if (existing == null || !existing.fingerprint().equals(fingerprint)) {
            throw new IllegalStateException("idempotency claim is missing or conflicts");
        }
        entries.put(key, new Entry(fingerprint, state, response, expiresAt));
    }

    private static void require(final String value, final String name, final int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void positive(final Duration value, final String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record Entry(
            String fingerprint,
            State state,
            GmOperationResponse response,
            Instant expiresAt) {
    }
}
