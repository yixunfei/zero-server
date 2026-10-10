package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** envelope 总预算先于任何字段解析；现有字段边界仍有效。 @author zn */
class KafkaEnvelopeBudgetTest {
    @Test void oversizedEnvelopeIsRejectedBeforeHeaderParsing() {
        var failure = assertThrows(ZeroException.class,
                () -> new KafkaRpcEnvelopeCodec().decode(new byte[KafkaRpcEnvelopeCodec.MAX_ENVELOPE_BYTES + 1]));
        assertEquals(RpcErrorCode.CODEC_FAILED, failure.errorCode());
        assertEquals("kafka rpc envelope is too large", failure.getCause().getMessage());
    }

    @Test void payloadBoundaryStillRoundTripsAndOversizeFailsEncoding() {
        var codec = new KafkaRpcEnvelopeCodec();
        var request = request(new byte[16 * 1024 * 1024]);
        assertEquals(request.payload().length, codec.decode(codec.encodeRequest(request)).request().payload().length);
        var failure = assertThrows(ZeroException.class, () -> codec.encodeRequest(request(new byte[16 * 1024 * 1024 + 1])));
        assertEquals(RpcErrorCode.CODEC_FAILED, failure.errorCode());
    }

    private static RpcRequest request(final byte[] payload) {
        return new RpcRequest("id", "reply", "service", "method", "trace", Instant.now().plusSeconds(60),
                RpcMode.REQUEST_RESPONSE, "", "", "", null, payload);
    }
}
