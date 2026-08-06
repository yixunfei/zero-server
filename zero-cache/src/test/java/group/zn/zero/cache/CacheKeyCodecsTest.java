package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 缓存 key codec 测试。
 *
 * @author zn
 */
class CacheKeyCodecsTest {

    /**
     * 验证标量 key 使用 k1 格式，并隔离不同 Java 类型。
     */
    @Test
    void scalarKeysShouldUseVersionedAndTypedFormat() {
        CacheKeyCodec<Object> codec = CacheKeyCodecs.defaults();

        String stringKey = codec.encode("1");
        String integerKey = codec.encode(1);
        String longKey = codec.encode(1L);
        String uuidKey = codec.encode(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertTrue(stringKey.startsWith("k1:str:"));
        assertEquals("k1:i32:1", integerKey);
        assertEquals("k1:i64:1", longKey);
        assertEquals("k1:uuid:00000000-0000-0000-0000-000000000001", uuidKey);
        assertNotEquals(stringKey, integerKey);
        assertNotEquals(integerKey, longKey);
    }

    /**
     * 验证 record 组合 key 使用字段顺序和字段名稳定编码。
     */
    @Test
    void recordKeysShouldEncodeComponentsWithoutRawSeparators() {
        CacheKeyCodec<PlayerProfileKey> codec = CacheKeyCodecs.defaults();
        PlayerProfileKey first = new PlayerProfileKey("s:1", "player:1001", 2L);
        PlayerProfileKey second = new PlayerProfileKey("s:1", "player:1001", 2L);

        String encoded = codec.encode(first);

        assertEquals(encoded, codec.encode(second));
        assertTrue(encoded.startsWith("k1:record:"));
        assertTrue(encoded.contains("c2VydmVySWQ"));
        assertTrue(encoded.contains("cGxheWVySWQ"));
        assertTrue(encoded.contains("dmVyc2lvbg"));
    }

    /**
     * 验证普通对象不会退化为默认 toString。
     */
    @Test
    void unsupportedObjectShouldFailFast() {
        CacheKeyCodec<Object> codec = CacheKeyCodecs.defaults();

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.encode(new Object()));

        assertEquals(CacheErrorCode.INVALID_KEY, exception.errorCode());
    }

    /**
     * 玩家资料组合 key。
     *
     * @param serverId 服务器 ID。
     * @param playerId 玩家 ID。
     * @param version 配置版本。
     */
    private record PlayerProfileKey(String serverId, String playerId, long version) {
    }
}
