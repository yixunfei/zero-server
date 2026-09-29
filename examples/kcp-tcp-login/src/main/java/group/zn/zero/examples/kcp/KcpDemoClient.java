package group.zn.zero.examples.kcp;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.kcp.KcpConnectInfo;
import group.zn.zero.net.kcp.KcpControlPlane;
import group.zn.zero.net.kcp.KcpRecovery;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** 受管客户端示例：TLS 登录、场景数据、重新领票、明确 TCP 回退。 @author zn */
public final class KcpDemoClient {
    private KcpDemoClient() { }
    /** @param args --demo TCP_PORT CERTIFICATE。 @throws Exception 联调失败，以非零退出报告。 */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("--demo")) throw new IllegalArgumentException("--demo TCP_PORT CERTIFICATE required");
        run(Integer.parseInt(args[1]), java.nio.file.Path.of(args[2]));
    }
    /** @param port 控制端口。 @param certificate 本次可信公钥证书。 @throws Exception 认证/传输失败。 */
    public static void run(final int port, final java.nio.file.Path certificate) throws Exception {
        try (var executors = ZeroRuntimeExecutors.localPrototype("kcp-client-demo", 1);
                SSLSocket socket = socket(port, certificate)) {
            KcpDemoWire.write(socket, KcpDemoWire.frame(KcpDemoWire.LOGIN, "demo-secret".getBytes(StandardCharsets.UTF_8)));
            var initial = new AtomicReference<>(readInfo(socket));
            var replies = new ArrayBlockingQueue<ProtocolFrame>(4);
            var control = new KcpControlPlane() {
                @Override public CompletionStage<KcpConnectInfo> acquire() {
                    KcpConnectInfo first = initial.getAndSet(null);
                    if (first != null) return CompletableFuture.completedFuture(first);
                    return CompletableFuture.supplyAsync(() -> {
                        synchronized (socket) {
                            try { KcpDemoWire.write(socket, KcpDemoWire.frame(KcpDemoWire.TICKET, new byte[0])); return readInfo(socket); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    }, executors.remoteIoExecutor());
                }
                @Override public CompletionStage<Void> revoke(final int conv) {
                    return CompletableFuture.runAsync(() -> {
                        synchronized (socket) {
                            try {
                                KcpDemoWire.write(socket, KcpDemoWire.frame(KcpDemoWire.REVOKE, ByteBuffer.allocate(4).putInt(conv).array()));
                                if (KcpDemoWire.read(socket).protocolId() != KcpDemoWire.REVOKE) throw new java.io.IOException("missing revoke acknowledgement");
                            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    }, executors.remoteIoExecutor());
                }
            };
            var recovery = new KcpRecovery(control, executors.logicExecutor(), (connection, frame) -> {
                if (!replies.offer(frame)) throw new IllegalStateException("demo reply queue full");
                return CompletableFuture.completedFuture(List.of());
            }, new ConnectionListener() { }, null);
            try {
                var client = recovery.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS);
                var config = client.connectInfo().options();
                byte[] payload = workload(config.profile(), config.maxMessageBytes());
                exchange(recovery, replies, 200, payload);
                recovery.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS);
                exchange(recovery, replies, 201, payload);
                recovery.fallbackToTcp().toCompletableFuture().get(3, TimeUnit.SECONDS);
                KcpDemoWire.write(socket, KcpDemoWire.frame(202, "new-request-after-fallback".getBytes(StandardCharsets.UTF_8)));
                if (KcpDemoWire.read(socket).protocolId() != 202) throw new IllegalStateException("TCP fallback failed");
                System.out.printf("PASS profile=%s bytes=%d reconnect=true fallback=TCP%n", config.profile(), payload.length);
            } finally { recovery.close().toCompletableFuture().join(); }
        }
    }
    private static void exchange(final KcpRecovery recovery, final ArrayBlockingQueue<ProtocolFrame> replies,
            final int id, final byte[] payload) throws Exception {
        recovery.send(KcpDemoWire.frame(id, payload)).toCompletableFuture().get(3, TimeUnit.SECONDS);
        ProtocolFrame reply = replies.poll(3, TimeUnit.SECONDS);
        if (reply == null || reply.protocolId() != id || !Arrays.equals(payload, reply.payload())) {
            throw new IllegalStateException("KCP business response mismatch");
        }
    }
    private static byte[] workload(final group.zn.zero.net.kcp.KcpProfile profile, final int maxFrame) {
        // 业务聚合发生在可靠发送之前；不能覆盖已进入 KCP 的消息。
        return switch (profile) {
            case LOW_LATENCY -> "tick=42;player=1;input=move-left".getBytes(StandardCharsets.UTF_8);
            case BALANCED -> "room=1;revision=43;aoi=[entity1:10,20;entity2:30,40]".getBytes(StandardCharsets.UTF_8);
            case MOBILE -> "request=44;resume-after-new-ticket".getBytes(StandardCharsets.UTF_8);
            case LOW_FREQUENCY -> "lobby=1;request=45;ready=true".getBytes(StandardCharsets.UTF_8);
            case BULK -> new byte[Math.min(32000, maxFrame - 64)];
        };
    }
    static KcpConnectInfo readInfo(final SSLSocket socket) throws java.io.IOException {
        ProtocolFrame frame = KcpDemoWire.read(socket);
        if (frame.protocolId() != KcpDemoWire.TICKET) throw new java.io.IOException("missing connection description");
        return KcpConnectInfo.decode(frame.payload());
    }
    static SSLSocket socket(final int port, final java.nio.file.Path certificate) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null, null);
        try (var input = Files.newInputStream(certificate)) {
            store.setCertificateEntry("local-demo", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, trust.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port);
        try {
            socket.setSoTimeout(3000); SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters); socket.startHandshake();
            return socket;
        } catch (Exception failure) { socket.close(); throw failure; }
    }
}
