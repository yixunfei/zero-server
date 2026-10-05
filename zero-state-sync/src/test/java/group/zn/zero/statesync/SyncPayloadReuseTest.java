package group.zn.zero.statesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** 深快照广播复用与既有摘要字节格式回归。 @author zn */
class SyncPayloadReuseTest {
    /** 源对象修改不能使广播共享内容或缓存摘要失效。 */
    @Test
    void preparedPayloadIsDeeplyImmutableAndSharedAcrossObservers() {
        List<Object> sourceList = new ArrayList<>(Arrays.asList(1, null, "two"));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("list", sourceList));
        Map<String, Object> prepared = SyncEnvelope.preparePayload(Map.of("nested", nested));
        SyncEnvelope first = envelope(prepared, "one");
        SyncEnvelope second = envelope(prepared, "two");
        assertSame(prepared, first.payload());
        assertSame(first.payload(), second.payload());
        assertSame(prepared, SyncEnvelope.preparePayload(prepared));
        assertSame(first.payloadHash(), second.payloadHash());
        assertEquals(1, prepared.size());
        assertTrue(prepared.containsKey("nested"));
        assertFalse(prepared.containsKey("absent"));
        assertNull(prepared.get("absent"));
        sourceList.clear();
        nested.clear();
        assertEquals(Map.of("nested", Map.of("list", Arrays.asList(1, null, "two"))), prepared);
        assertThrows(UnsupportedOperationException.class, () -> prepared.put("new", 1));
        Map<?, ?> frozenNested = (Map<?, ?>) prepared.get("nested");
        assertThrows(UnsupportedOperationException.class, frozenNested::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) frozenNested.get("list"))::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.entrySet().iterator().next().setValue(2));
    }

    /** 只对框架可证明不可变的内容缓存，公共显式摘要始终校验。 */
    @Test
    void mutableLeavesAndForgedHashesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncEnvelope.preparePayload(Map.of("x", new StringBuilder("mutable"))));
        assertThrows(IllegalArgumentException.class,
                () -> SyncEnvelope.preparePayload(Map.of("x", new int[] {1})));
        Map<String, Object> prepared = SyncEnvelope.preparePayload(Map.of("x", List.of(1, 2)));
        SyncEnvelope original = envelope(prepared, "one");
        assertThrows(IllegalArgumentException.class, () -> new SyncEnvelope("scene", "two", 2, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, prepared, "00"));
        assertEquals(original.payloadHash(), new SyncEnvelope("scene", "two", 2, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, prepared, original.payloadHash().toUpperCase()).payloadHash());
    }

    /** 固定 canonical 字节验证标量、排序、数组、嵌套空值及转义不改变历史 hash。 */
    @Test
    void hashesRetainCanonicalFormat() throws Exception {
        assertHash(Map.of("value", 1), "{\"value\":\"1\"}");
        assertHash(Map.of("b", true, "a", "x"), "{\"a\":\"x\",\"b\":\"true\"}");
        assertHash(Map.of("array", new int[] {1, 2}), "{\"array\":\"[[1, 2]]\"}");
        assertHash(Map.of("items", Arrays.asList(null, 1)), "{\"items\":[null,\"1\"]}");
        assertHash(Map.of("quote", "a\"b\\c"), "{\"quote\":\"a\\\"b\\\\c\"}");
        Map<String, Object> normal = Map.of("nested", Map.of("z", 9, "a", List.of(1, 2)));
        assertEquals(envelope(normal, "one").payloadHash(),
                envelope(SyncEnvelope.preparePayload(normal), "two").payloadHash());
    }

    /** 每线程复用 digest 时，各调用内容不能互相污染。 */
    @Test
    void digestReuseIsIsolatedAcrossConcurrentCalls() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<String>> hashes = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int value = index;
                hashes.add(executor.submit(() -> envelope(Map.of("value", value), "o").payloadHash()));
            }
            for (int index = 0; index < hashes.size(); index++) {
                assertEquals(sha256("{\"value\":\"" + index + "\"}"), hashes.get(index).get());
            }
        }
    }

    /** 固定测试信封。 */
    private SyncEnvelope envelope(final Map<String, Object> payload, final String observer) {
        return new SyncEnvelope("scene", observer, 1, 0, 1, SyncEnvelope.Kind.SNAPSHOT, payload);
    }

    /** 与明确的 canonical 字节而非另一份规范化算法比较。 */
    private void assertHash(final Map<String, Object> payload, final String canonical) throws Exception {
        assertEquals(sha256(canonical), envelope(payload, "o").payloadHash());
    }

    /** 使用独立 JCA 实例计算固定测试向量。 */
    private String sha256(final String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
