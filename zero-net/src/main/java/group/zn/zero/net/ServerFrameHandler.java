package group.zn.zero.net;

import group.zn.zero.protocol.ProtocolFrame;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 网络协议帧处理器。
 *
 * <p>实现应避免执行不可控阻塞 IO。Netty 实现会把调用切换到业务执行器，
 * 以避免在 IO 线程中执行阻塞逻辑。
 *
 * @author zn
 */
@FunctionalInterface
public interface ServerFrameHandler {

    /**
     * 处理协议帧。
     *
     * @param connection 连接；不可为空。
     * @param frame 协议帧；不可为空。
     * @return 待发送响应帧列表；不可为空；列表有序、可能为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<List<ProtocolFrame>> handle(IConnection connection, ProtocolFrame frame);
}
