package group.zn.zero.examples.centerlogic.kafka.common;

import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.codec.RpcCodecRegistry;

/** Shared codec registration for the common-contract messages. */
public final class CenterContractCodecs {
    private CenterContractCodecs() { }

    /** Registers all common-contract DTO codecs in a caller-owned registry. */
    public static void register(final RpcCodecRegistry registry) {
        registry.register(CenterRegistration.class, definition(9201, "registration"),
                new GeneratedProtocolCodec<>(new RegistrationCodec()));
        registry.register(CenterHeartbeat.class, definition(9202, "heartbeat"),
                new GeneratedProtocolCodec<>(new HeartbeatCodec()));
        registry.register(CenterAck.class, definition(9203, "ack"),
                new GeneratedProtocolCodec<>(new AckCodec()));
    }

    private static ProtocolDefinition definition(final int id, final String name) {
        return new ProtocolDefinition(id, "onboarding." + name, ProtocolDirection.CLIENT_TO_SERVER, 1);
    }

    private abstract static class Codec<T> implements ZeroPayloadCodec<T> {
        @Override public int priority() { return 0; }
        @Override public int estimatedSize(final T value) { return 256; }
    }
    private static final class RegistrationCodec extends Codec<CenterRegistration> {
        public Class<CenterRegistration> messageType() { return CenterRegistration.class; }
        public String name() { return "center-registration"; }
        public void write(final ZeroWriter w, final CenterRegistration v) { w.writeString(v.instanceId()); w.writeString(v.serviceName()); w.writeString(v.traceId()); }
        public CenterRegistration read(final ZeroReader r) { return new CenterRegistration(r.readString(), r.readString(), r.readString()); }
    }
    private static final class HeartbeatCodec extends Codec<CenterHeartbeat> {
        public Class<CenterHeartbeat> messageType() { return CenterHeartbeat.class; }
        public String name() { return "center-heartbeat"; }
        public void write(final ZeroWriter w, final CenterHeartbeat v) { w.writeString(v.instanceId()); w.writeString(v.traceId()); w.writeLong(v.observedAtEpochMillis()); }
        public CenterHeartbeat read(final ZeroReader r) { return new CenterHeartbeat(r.readString(), r.readString(), r.readLong()); }
    }
    private static final class AckCodec extends Codec<CenterAck> {
        public Class<CenterAck> messageType() { return CenterAck.class; }
        public String name() { return "center-ack"; }
        public void write(final ZeroWriter w, final CenterAck v) { w.writeString(v.instanceId()); w.writeString(v.state()); w.writeString(v.traceId()); }
        public CenterAck read(final ZeroReader r) { return new CenterAck(r.readString(), r.readString(), r.readString()); }
    }
}
