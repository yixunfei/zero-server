package group.zn.zero.data.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoCollectionIsolationTest {
    private final Map<String, Set<String>> idsByCollection = new HashMap<>();

    @Test
    void logicalNamesMustKeepSameIdsInIndependentCollections() {
        MongoDatabase database = database("review");
        for (List<String> pair : List.of(List.of("tenant-a", "balances"), List.of("tenant_a", "balances"),
                List.of("a__b", "c"), List.of("a", "b__c"), List.of("\u4e16\u754c", "balances"))) {
            var store = new MongoDriverEnvelopeStore(database, pair.get(0), pair.get(1));
            assertTrue(store.saveIfVersion(new ZeroDataEnvelope(pair.get(0), pair.get(1), "same-id",
                    1, 1, 1, 0, new byte[] {1}), 0));
        }
        assertEquals(5, idsByCollection.size());
        new MongoDriverEnvelopeStore(database, "tenant-a", "balances");
        assertEquals(5, idsByCollection.size());
    }

    @Test
    void namesMustFitTheDatabaseNamespaceLimitBeforeSelectingACollection() {
        MongoDatabase database = database("db");
        new MongoDriverEnvelopeStore(database, "a".repeat(113), "b");
        assertEquals(235, 3 + idsByCollection.keySet().iterator().next().length());
        assertThrows(IllegalArgumentException.class,
                () -> new MongoDriverEnvelopeStore(database, "a".repeat(114), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> new MongoDriverEnvelopeStore(database, "\ud800", "b"));
        assertEquals(1, idsByCollection.size());
    }

    private MongoDatabase database(final String name) {
        return (MongoDatabase) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MongoDatabase.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getCollection" -> collection((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private MongoCollection<?> collection(final String name) {
        Set<String> ids = idsByCollection.computeIfAbsent(name, key -> new HashSet<>());
        return (MongoCollection<?>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MongoCollection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("insertOne")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    if (!ids.add(((Document) args[0]).getString("_id"))) {
                        throw new MongoWriteException(new WriteError(11000, "duplicate id", new BsonDocument()), new ServerAddress());
                    }
                    return null;
                });
    }
}
