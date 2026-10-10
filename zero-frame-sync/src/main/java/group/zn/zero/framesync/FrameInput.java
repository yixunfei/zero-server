package group.zn.zero.framesync;

import java.util.Objects;
import java.util.Arrays;

/** Immutable client input accepted by the frame runtime. */
public record FrameInput(String uid, long inputSeq, long targetFrame, long clientFrame, byte[] payload, String traceId) {
    public FrameInput {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(traceId, "traceId");
        if (uid.isBlank() || inputSeq < 0 || targetFrame < 0 || clientFrame < 0) throw new IllegalArgumentException("invalid input");
        payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
    @Override public boolean equals(final Object other) {
        return other instanceof FrameInput input && inputSeq == input.inputSeq && targetFrame == input.targetFrame
                && clientFrame == input.clientFrame && uid.equals(input.uid) && traceId.equals(input.traceId)
                && Arrays.equals(payload, input.payload);
    }
    @Override public int hashCode() {
        int result = Objects.hash(uid, inputSeq, targetFrame, clientFrame, traceId);
        return 31 * result + Arrays.hashCode(payload);
    }
    /** 返回负载字节数；只读、无复制、线程安全。 @return 非负长度。 */
    public int payloadLength() { return payload.length; }
}
