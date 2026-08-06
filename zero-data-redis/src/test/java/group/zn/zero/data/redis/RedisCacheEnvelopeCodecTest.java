package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/**
 * Redis 缓存信封 codec 测试。
 *
 * @author zn
 */
class RedisCacheEnvelopeCodecTest {

    /**
     * 验证正常缓存信封可以往返编码。
     */
    @Test
    void envelopeCodecShouldRoundTripNormalEntry() {
        RedisCacheEnvelopeCodec codec = new RedisCacheEnvelopeCodec();
        RedisCacheEnvelope envelope = new RedisCacheEnvelope(
                1L,
                2L,
                1,
                "string",
                1000L,
                2000L,
                false,
                new byte[] {1, 2, 3});

        RedisCacheEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals(envelope.cacheVersion(), decoded.cacheVersion());
        assertEquals(envelope.entityVersion(), decoded.entityVersion());
        assertEquals(envelope.codecName(), decoded.codecName());
        assertArrayEquals(envelope.payload(), decoded.payload());
    }

    /**
     * 验证负缓存信封可以往返编码。
     */
    @Test
    void envelopeCodecShouldRoundTripNegativeEntry() {
        RedisCacheEnvelopeCodec codec = new RedisCacheEnvelopeCodec();
        RedisCacheEnvelope envelope = new RedisCacheEnvelope(
                1L,
                0L,
                1,
                "string",
                1000L,
                2000L,
                true,
                new byte[0]);

        RedisCacheEnvelope decoded = codec.decode(codec.encode(envelope));

        assertTrue(decoded.negative());
        assertEquals(0, decoded.payload().length);
    }

    /**
     * 验证损坏信封解码失败时绑定缓存反序列化错误码。
     */
    @Test
    void envelopeCodecShouldBindErrorCodeWhenDecodeFails() {
        RedisCacheEnvelopeCodec codec = new RedisCacheEnvelopeCodec();

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.decode(new byte[] {1, 2, 3}));

        assertEquals(CacheErrorCode.DESERIALIZE_FAILED, exception.errorCode());
    }
}
