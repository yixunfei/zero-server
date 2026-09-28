package group.zn.zero.data.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 真实 MongoDB 上验证版本保护及两个独立客户端的 CAS 竞争。 @author zn */
class MongoVersionBoundaryExternalIT {
    /** 低版本不能覆盖，等版本可重放，高版本可保存；两个客户端只有一个 CAS 成功。 */
    @Test void savesRemainMonotonicAndCasHasOneWinner() throws Exception {
        var settings = MongoDriverSettings.fromSystemProperties();
        var adapter = new MongoDataAdapter();
        try (var a = adapter.createClient(settings); var b = adapter.createClient(settings)) {
            var first = new MongoDriverEnvelopeStore(a.getDatabase(settings.databaseName()), "audit_followup", "versions");
            var second = new MongoDriverEnvelopeStore(b.getDatabase(settings.databaseName()), "audit_followup", "versions");
            String id = UUID.randomUUID().toString();
            try {
                first.save(envelope(id, 3, 3));
                ZeroException failure = assertThrows(ZeroException.class, () -> second.save(envelope(id, 2, 2)));
                assertEquals(DataErrorCode.VERSION_CONFLICT, failure.errorCode());
                assertArrayEquals(new byte[]{3}, first.findById(id).orElseThrow().payload());
                second.save(envelope(id, 3, 9));
                assertArrayEquals(new byte[]{9}, first.findById(id).orElseThrow().payload());
                second.save(envelope(id, 4, 4));
                assertEquals(1, compete(first, second, id, 4));
                assertEquals(5, first.findById(id).orElseThrow().version());
                first.deleteById(id);
                assertEquals(1, compete(first, second, id, 0));
                assertEquals(1, second.findById(id).orElseThrow().version());
            } finally { first.deleteById(id); }
        }
    }

    private int compete(ZeroDataEnvelopeStore first, ZeroDataEnvelopeStore second, String id, long expected)
            throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                ready.countDown(); start.await();
                return first.saveIfVersion(envelope(id, expected + 1, 10), expected);
            });
            var b = executor.submit(() -> {
                ready.countDown(); start.await();
                return second.saveIfVersion(envelope(id, expected + 1, 20), expected);
            });
            try {
                org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally { start.countDown(); }
            return (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
        }
    }

    private ZeroDataEnvelope envelope(String id, long version, int payload) {
        return new ZeroDataEnvelope("audit_followup", "versions", id, version, 1, 1, 0, new byte[]{(byte) payload});
    }
}
