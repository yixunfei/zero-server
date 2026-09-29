package group.zn.zero.net.kcp;

import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.security.SecurityContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 仅用于算法/异常测试的控制连接；真实 TLS 登录另外单独验证。 @author zn */
final class KcpTestControl implements IConnection {
    /** 唯一控制连接号。 */
    private final String id = UUID.randomUUID().toString();
    /** 线程安全属性。 */
    private final ConnectionAttributes attributes = new DefaultConnectionAttributes();
    KcpTestControl() {
        attributes.put(ProductionNetworkConnectionAttributes.STATE, ConnectionLifecycleState.ESTABLISHED);
        attributes.put(ProductionNetworkConnectionAttributes.SUBJECT_ID, "test-player");
        attributes.put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, true);
        Instant now = Instant.now();
        attributes.put(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT, new SecurityContext("test-player",
                now, now.plusSeconds(60), "tcp", "127.0.0.1", "127.0.0.1", "trace", id,
                Set.of("network.request"), Map.of()));
    }
    @Override public String connectionId() { return id; }
    @Override public ServerType serverType() { return ServerType.TCP; }
    @Override public SocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 20000); }
    @Override public SocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 10000); }
    @Override public ConnectionAttributes attributes() { return attributes; }
    @Override public CompletionStage<Void> send(final Object message) { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> close() {
        attributes.put(ProductionNetworkConnectionAttributes.STATE, ConnectionLifecycleState.CLOSED);
        return CompletableFuture.completedFuture(null);
    }
}
