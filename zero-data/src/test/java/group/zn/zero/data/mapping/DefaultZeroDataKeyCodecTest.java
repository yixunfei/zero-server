package group.zn.zero.data.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 默认数据键编码器测试。
 *
 * @author zn
 */
class DefaultZeroDataKeyCodecTest {

    /**
     * 验证不同基础类型编码不会互相冲突。
     */
    @Test
    void codecShouldSeparateStringAndNumberKeySpace() {
        DefaultZeroDataKeyCodec codec = new DefaultZeroDataKeyCodec();

        String stringKey = codec.encode("1");
        String intKey = codec.encode(1);
        String longKey = codec.encode(1L);
        String shortKey = codec.encode((short) 1);
        String byteKey = codec.encode((byte) 1);

        assertNotEquals(stringKey, intKey);
        assertNotEquals(intKey, longKey);
        assertNotEquals(longKey, shortKey);
        assertNotEquals(shortKey, byteKey);
        assertEquals("1", codec.decode(stringKey));
        assertEquals(1, codec.decode(intKey));
        assertEquals(1L, codec.decode(longKey));
        assertEquals((short) 1, codec.decode(shortKey));
        assertEquals((byte) 1, codec.decode(byteKey));
    }

    /**
     * 验证字符串 key 对特殊字符和空字符串保持可逆。
     */
    @Test
    void codecShouldRoundTripStringValuesWithoutDelimiterAmbiguity() {
        DefaultZeroDataKeyCodec codec = new DefaultZeroDataKeyCodec();
        String value = "player:{1}:道具:001";

        String encoded = codec.encode(value);

        assertTrue(encoded.startsWith("str:"));
        assertEquals(-1, encoded.indexOf('{'));
        assertEquals(-1, encoded.indexOf('}'));
        assertEquals(value, codec.decode(encoded));
        assertEquals("", codec.decode(codec.encode("")));
    }

    /**
     * 验证 UUID 编码可逆。
     */
    @Test
    void codecShouldRoundTripUuid() {
        DefaultZeroDataKeyCodec codec = new DefaultZeroDataKeyCodec();
        UUID uuid = UUID.randomUUID();

        String encoded = codec.encode(uuid);

        assertEquals(uuid, codec.decode(encoded));
    }

    /**
     * 验证不支持的 key 类型会被拒绝。
     */
    @Test
    void codecShouldRejectUnsupportedKeyType() {
        DefaultZeroDataKeyCodec codec = new DefaultZeroDataKeyCodec();

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.encode(new Object()));

        assertEquals(DataErrorCode.MAPPING_INVALID, exception.errorCode());
    }

    /**
     * 验证非法编码会暴露统一数据错误码。
     */
    @Test
    void codecShouldRejectInvalidEncodedValue() {
        DefaultZeroDataKeyCodec codec = new DefaultZeroDataKeyCodec();

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.decode("uuid:bad"));

        assertEquals(DataErrorCode.MAPPING_INVALID, exception.errorCode());
    }

    /**
     * 验证 UUID 生成器生成合法 UUID 字符串。
     */
    @Test
    void uuidGeneratorShouldCreateUuidString() {
        UuidZeroDataKeyGenerator generator = new UuidZeroDataKeyGenerator();

        String value = generator.generate();

        assertEquals(value, UUID.fromString(value).toString());
    }
}
