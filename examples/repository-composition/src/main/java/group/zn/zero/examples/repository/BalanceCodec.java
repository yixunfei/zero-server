package group.zn.zero.examples.repository;

import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;

public final class BalanceCodec implements ZeroPayloadCodec<Balance> {
    @Override public String name() { return "balance-example"; }
    @Override public Class<Balance> messageType() { return Balance.class; }

    @Override
    public void write(final ZeroWriter writer, final Balance value) {
        writer.writeString(value.id());
        writer.writeLong(value.version());
        writer.writeLong(value.amount());
    }

    @Override
    public Balance read(final ZeroReader reader) {
        return new Balance(reader.readString(), reader.readLong(), reader.readLong());
    }
}
