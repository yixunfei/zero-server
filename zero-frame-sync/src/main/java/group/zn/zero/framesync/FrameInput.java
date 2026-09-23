package group.zn.zero.framesync;

import java.util.Objects;

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
    /** 返回负载字节数；只读、无复制、线程安全。 @return 非负长度。 */
    public int payloadLength() { return payload.length; }
}
