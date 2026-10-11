package group.zn.zero.starter.production;

import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 测试专用 Redis TCP 代理；断开现有连接并拒绝新连接，不触碰共享容器。 */
final class RedisRecoveryFaultProxy implements AutoCloseable {
    /** 实际后端地址，仅在测试中使用。 */
    private final String backendHost;
    /** 实际后端 TCP 端口。 */
    private final int backendPort;
    /** 代理 IO 的统一受管执行器。 */
    private final ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("redis-recovery-proxy");
    /** 只监听本机的测试端口。 */
    private final ServerSocket server;
    /** 活动连接，停止与故障切换时统一关闭。 */
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    /** 故障注入模式，跨 IO 线程可见。 */
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.CONNECTED);

    /** 绑定本地临时端口并开始转发；传入真实 Redis 后端地址。 */
    RedisRecoveryFaultProxy(final String backendHost, final int backendPort) throws IOException {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        executors.remoteIoExecutor().execute(this::accept);
    }

    /** 返回仅本机可访问的代理端口。 */
    int port() { return server.getLocalPort(); }

    /** 注入断连，后续连接在恢复前也会关闭。 */
    void disconnect() {
        mode.set(Mode.DISCONNECTED);
        sockets.forEach(RedisRecoveryFaultProxy::closeQuietly);
    }

    /** 模拟网络请求无响应，等待真实 Jedis socket 超时。 */
    void blackhole() {
        mode.set(Mode.BLACKHOLED);
        sockets.forEach(RedisRecoveryFaultProxy::closeQuietly);
    }

    /** 恢复到同一真实 Redis 服务的转发。 */
    void reconnect() {
        sockets.forEach(RedisRecoveryFaultProxy::closeQuietly);
        mode.set(Mode.CONNECTED);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                if (mode.get() == Mode.DISCONNECTED) { closeQuietly(client); continue; }
                if (mode.get() == Mode.BLACKHOLED) { sockets.add(client); continue; }
                Socket backend = new Socket(backendHost, backendPort);
                sockets.add(client);
                sockets.add(backend);
                executors.remoteIoExecutor().execute(() -> copy(client, backend));
                executors.remoteIoExecutor().execute(() -> copy(backend, client));
            } catch (IOException failure) {
                if (!server.isClosed()) throw new IllegalStateException("Redis test proxy accept failed", failure);
            }
        }
    }

    private void copy(final Socket source, final Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
        } catch (IOException ignored) {
            // The test intentionally closes live sockets to simulate a remote disconnect.
        } finally {
            closeQuietly(source);
            closeQuietly(destination);
            sockets.remove(source);
            sockets.remove(destination);
        }
    }

    /** 停止只属于本测试的代理和受管执行域。 */
    @Override public void close() {
        closeQuietly(server);
        sockets.forEach(RedisRecoveryFaultProxy::closeQuietly);
        executors.close();
    }

    private static void closeQuietly(final AutoCloseable closeable) {
        try { closeable.close(); }
        catch (Exception ignored) { /* Connection shutdown is expected during fault injection. */ }
    }

    /** 测试网络可用性。 */
    private enum Mode { CONNECTED, DISCONNECTED, BLACKHOLED }
}
