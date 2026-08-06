package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 数据对象 zcode 信封 codec 测试。
 *
 * @author zn
 */
class ZeroDataEnvelopeCodecTest {

    /**
     * 验证信封线格式可逆。
     */
    @Test
    void codecShouldRoundTripEnvelope() {
        ZeroDataEnvelope envelope = new ZeroDataEnvelope(
                "game",
                "player",
                "str:cDE",
                7L,
                1,
                1,
                1000L,
                new byte[] {1, 2, 3});
        ZeroDataEnvelopeCodec codec = new ZeroDataEnvelopeCodec();

        ZeroDataEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals(envelope.namespace(), decoded.namespace());
        assertEquals(envelope.collection(), decoded.collection());
        assertEquals(envelope.id(), decoded.id());
        assertEquals(envelope.version(), decoded.version());
        assertEquals(envelope.schemaVersion(), decoded.schemaVersion());
        assertEquals(envelope.codecVersion(), decoded.codecVersion());
        assertEquals(envelope.encodedAtEpochMillis(), decoded.encodedAtEpochMillis());
        assertArrayEquals(envelope.payload(), decoded.payload());
    }

    /**
     * 验证非法信封会暴露数据读取错误码。
     */
    @Test
    void codecShouldRejectInvalidEnvelopeBytes() {
        ZeroDataEnvelopeCodec codec = new ZeroDataEnvelopeCodec();

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.decode(new byte[] {1, 2, 3}));

        assertEquals(DataErrorCode.READ_FAILED, exception.errorCode());
    }
}
