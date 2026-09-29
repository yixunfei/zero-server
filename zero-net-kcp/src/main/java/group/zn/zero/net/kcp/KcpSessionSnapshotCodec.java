package group.zn.zero.net.kcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;

/** 有界迁移元数据格式；不含密钥/业务帧，只在可信会话存储使用，线程安全。 @author zn */
public final class KcpSessionSnapshotCodec {
    private KcpSessionSnapshotCodec() { }
    /** @param value 不可变快照。 @return 独立有序字节；非法/过长元数据抛出参数异常，无外部修改。 */
    public static byte[] encode(final KcpSessionSnapshot value) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(0x5a4b5353); out.writeByte(1); out.writeInt(value.conv()); out.writeLong(value.generation());
            out.writeUTF(value.owner()); out.writeUTF(value.subject());
            byte[] address = value.remote().getAddress().getAddress(); out.writeByte(address.length); out.write(address);
            out.writeInt(value.remote().getPort()); out.writeLong(value.expiresAt().toEpochMilli());
            byte[] window = value.receiveWindow(); out.writeByte(window.length); out.write(window);
            out.writeLong(value.sentSequence()); out.writeLong(value.receivedSequence()); out.flush();
            if (bytes.size() > 2048) throw new IllegalArgumentException("snapshot too large");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalArgumentException("invalid session snapshot", failure); }
    }
    /** @param bytes 完整可信快照，最多 2048 字节。 @return 不可变快照；截断、超长或非法版本拒绝。 */
    public static KcpSessionSnapshot decode(final byte[] bytes) {
        if (bytes == null || bytes.length > 2048) throw new IllegalArgumentException("invalid snapshot size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x5a4b5353 || in.readUnsignedByte() != 1) throw new IllegalArgumentException("invalid snapshot version");
            int conv = in.readInt(); long generation = in.readLong(); String owner = in.readUTF(), subject = in.readUTF();
            int length = in.readUnsignedByte();
            if (length != 4 && length != 16) throw new IllegalArgumentException("invalid IP address");
            byte[] address = new byte[length]; in.readFully(address);
            var remote = new InetSocketAddress(InetAddress.getByAddress(address), in.readInt());
            Instant expiry = Instant.ofEpochMilli(in.readLong()); byte[] window = new byte[in.readUnsignedByte()]; in.readFully(window);
            var value = new KcpSessionSnapshot(conv, generation, owner, subject, remote, expiry, window, in.readLong(), in.readLong());
            if (in.available() != 0) throw new IllegalArgumentException("trailing snapshot bytes"); return value;
        } catch (IOException failure) { throw new IllegalArgumentException("malformed snapshot", failure); }
    }
}
