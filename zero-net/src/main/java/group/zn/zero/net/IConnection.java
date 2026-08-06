package group.zn.zero.net;

import group.zn.zero.protocol.ProtocolFrame;
import java.net.SocketAddress;
import java.util.concurrent.CompletionStage;

/**
 * 传输层连接抽象。
 *
 * <p>该接口只描述网络连接，不代表业务 session。
 *
 * @author zn
 */
public interface IConnection {

    /**
     * 返回连接标识。
     *
     * @return 连接标识；不可为空；线程安全。
     */
    String connectionId();

    /**
     * 返回服务器类型。
     *
     * @return 服务器类型；不可为空；线程安全。
     */
    ServerType serverType();

    /**
     * 返回远端地址。
     *
     * @return 远端地址；可能为空；线程安全。
     */
    SocketAddress remoteAddress();

    /**
     * 返回本地地址。
     *
     * @return 本地地址；可能为空；线程安全。
     */
    SocketAddress localAddress();

    /**
     * 返回连接属性容器。
     *
     * @return 连接属性；不可为空；线程安全。
     */
    ConnectionAttributes attributes();

    /**
     * 发送消息。
     *
     * @param message 消息对象；不可为空。
     * @return 发送完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 发送失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> send(Object message);

    /**
     * 发送协议帧。
     *
     * @param frame 协议帧；不可为空。
     * @return 发送完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 发送失败时抛出，必须绑定 ErrorCode。
     */
    default CompletionStage<Void> sendFrame(final ProtocolFrame frame) {
        return send(frame);
    }

    /**
     * 关闭连接。
     *
     * @return 关闭完成信号；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Void> close();
}
