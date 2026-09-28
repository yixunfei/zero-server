package group.zn.zero.data.redis;

import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;

/**
 * Explicitly local Redis-shaped envelope store for tests and single-process
 * prototypes. It does not provide Redis durability or multi-instance sharing.
 *
 * @author zn
 */
public final class InMemoryRedisDataEnvelopeStore extends RedisDataEnvelopeStore {

    /**
     * Creates a local store with the default key strategy and codec.
     *
     * @param namespace data namespace; non-blank.
     * @param collection collection name; non-blank.
     */
    public InMemoryRedisDataEnvelopeStore(final String namespace, final String collection) {
        super(namespace, collection);
    }

    /**
     * Creates a local store with explicit collaborators.
     *
     * @param namespace data namespace; non-blank.
     * @param collection collection name; non-blank.
     * @param keyStrategy key strategy; non-null.
     * @param localJournal optional local journal.
     * @param envelopeCodec envelope codec; non-null.
     */
    public InMemoryRedisDataEnvelopeStore(
            final String namespace,
            final String collection,
            final RedisDataKeyStrategy keyStrategy,
            final LocalDiskDataJournal localJournal,
            final ZeroDataEnvelopeCodec envelopeCodec) {
        super(namespace, collection, keyStrategy, localJournal, envelopeCodec);
    }
}
