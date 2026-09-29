package group.zn.zero.examples.kcp;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.net.ssl.SSLSocket;

/** 示例应用自行拥有的协议 ID 和 TCP framing，不是框架保留协议。 @author zn */
final class KcpDemoWire {
    /** 示例登录。 */
    static final int LOGIN = 1;
    /** 请求或响应连接描述。 */
    static final int TICKET = 100;
    /** 撤销确认。 */
    static final int REVOKE = 101;
    /** 无状态共享 codec。 */
    static final ZeroBinaryFrameCodec CODEC = new ZeroBinaryFrameCodec();
    private KcpDemoWire() { }
    static void write(final SSLSocket socket, final ProtocolFrame frame) throws java.io.IOException {
        byte[] bytes = CODEC.encode(frame);
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    static ProtocolFrame read(final SSLSocket socket) throws java.io.IOException {
        var input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        if (length < 1 || length > 131072) throw new java.io.IOException("invalid demo frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new java.io.EOFException("incomplete demo frame");
        return CODEC.decode(bytes);
    }
    static ProtocolFrame frame(final int id, final byte[] bytes) { return new ProtocolFrame(id, 1, 0, null, bytes); }
}
