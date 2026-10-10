package group.zn.zero.data.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore.ConditionalWrite;
import group.zn.zero.data.model.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Mongo 真实批次与分页验证；逐项 CAS 冲突准确返回 false，后续项继续且同 ID 保序。 @author zn */
class MongoBatchPageExternalIT {

    /** 使用独立临时库，验证跨分块顺序、页外损坏隔离和有序 bulk 冲突。 */
    @Test
    void batchPageAndPartialConflictPreserveContracts() {
        var settings = MongoDriverSettings.fromSystemProperties();
        try (var client = new MongoDataAdapter().createClient(settings)) {
            var database = client.getDatabase("perf" + UUID.randomUUID().toString().replace("-", ""));
            var store = new MongoDriverEnvelopeStore(database, "performance", "batch");
            try {
                List<String> ids = IntStream.range(0, 300).mapToObj(i -> String.format("p%03d", i)).toList();
                var writes = ids.stream().map(id -> new ConditionalWrite(envelope(id, 1), 0)).toList();
                assertTrue(store.saveIfVersionBatch(writes).stream().allMatch(Boolean.TRUE::equals));
                var queries = new ArrayList<>(ids.reversed());
                queries.addAll(List.of("absent", "p000"));
                var expected = new ArrayList<>(ids.reversed());
                expected.add("p000");
                assertEquals(expected, store.findByIds(queries).stream().map(ZeroDataEnvelope::id).toList());

                String physicalName = database.listCollectionNames().first();
                var documents = database.getCollection(physicalName);
                documents.updateOne(Filters.eq("_id", "p299"), Updates.set("version", 0L));
                var page = store.findPage(new PageRequest(100, 7));
                assertEquals(ids.subList(100, 107), page.items().stream().map(ZeroDataEnvelope::id).toList());
                assertEquals("107", page.nextCursor());
                assertEquals(300, page.total());
                assertThrows(RuntimeException.class, () -> store.findById("p299"));
                documents.updateOne(Filters.eq("_id", "p299"), Updates.set("version", 1L));
                assertTrue(store.findPage(new PageRequest(Integer.MAX_VALUE, 7)).items().isEmpty());

                var mixed = List.of(new ConditionalWrite(envelope("p000", 2), 1),
                        new ConditionalWrite(envelope("p001", 2), 9),
                        new ConditionalWrite(envelope("p301", 1), 0),
                        new ConditionalWrite(envelope("p000", 3), 2));
                assertEquals(List.of(true, false, true, true), store.saveIfVersionBatch(mixed));
                assertEquals(3, store.findById("p000").orElseThrow().version());
                assertEquals(1, store.findById("p001").orElseThrow().version());
                assertTrue(store.findById("p301").isPresent());
                var duplicate = List.of(new ConditionalWrite(envelope("p302", 1), 0),
                        new ConditionalWrite(envelope("p000", 1), 0),
                        new ConditionalWrite(envelope("p303", 1), 0));
                assertEquals(List.of(true, false, true), store.saveIfVersionBatch(duplicate));
                assertTrue(store.findById("p302").isPresent());
                assertTrue(store.findById("p303").isPresent());
                var deletes = new ArrayList<>(ids);
                deletes.addAll(List.of("p301", "p302", "p303", "p000", "absent"));
                store.deleteAll(deletes);
                assertEquals(0, store.count());
            } finally {
                database.drop();
            }
        }
    }

    /** 创建测试信封；所有命名空间均属于独立临时库。 */
    private ZeroDataEnvelope envelope(final String id, final long version) {
        return new ZeroDataEnvelope("performance", "batch", id, version, 1, 1, 1, new byte[] {1});
    }
}
