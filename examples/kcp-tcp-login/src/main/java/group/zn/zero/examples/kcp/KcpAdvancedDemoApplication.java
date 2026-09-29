package group.zn.zero.examples.kcp;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.kcp.InMemoryKcpSessionStore;
import group.zn.zero.net.kcp.KcpClient;
import group.zn.zero.net.kcp.KcpConnectInfo;
import group.zn.zero.net.kcp.KcpFecOptions;
import group.zn.zero.net.kcp.KcpOptions;
import group.zn.zero.net.kcp.KcpPathOptions;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.net.kcp.KcpSessionServices;
import group.zn.zero.net.kcp.KcpSessionStore;
import group.zn.zero.net.kcp.KcpTransportOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** 双节点真实 TLS/UDP 示例；会话存储可替换，票据只从已认证控制面获取。 @author zn */
public final class KcpAdvancedDemoApplication {
    private KcpAdvancedDemoApplication() { }
    /**
     * 运行指定的内置策略组合或全部组合；仅本机演示，失败以异常结束。
     * @param args --demo [HMAC|CHACHA|AES|ALL] [NONE|XOR|RS]。
     * @throws Exception TLS、路径验证、排空或跨节点接管失败。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--demo")) throw new IllegalArgumentException("explicit --demo required");
        String selected = args.length > 1 ? args[1] : "ALL";
        if (selected.equals("ALL")) {
            for (String protection : List.of("HMAC", "CHACHA", "AES")) {
                for (String fec : List.of("NONE", "XOR", "RS")) run(new InMemoryKcpSessionStore(), protection, fec);
            }
        } else run(new InMemoryKcpSessionStore(), selected, args.length > 2 ? args[2] : "RS");
    }
    /**
     * 在两个独立监听器间完成受控移交，存储/执行器生命周期由组合根负责。
     * @param store 两节点共享的所有权 SPI，本方法不关闭它。
     * @param protection HMAC/CHACHA/AES。 @param fec NONE/XOR/RS。
     * @throws Exception 任意端到端校验失败；调用线程可阻塞，不在 IO/Actor 调用。
     */
    public static void run(final KcpSessionStore store, final String protection, final String fec) throws Exception {
        KcpOptions options = options(protection, fec);
        var sourceServices = new KcpSessionServices("source", store, Duration.ofSeconds(30), Duration.ofSeconds(3));
        var targetServices = new KcpSessionServices("target", store, Duration.ofSeconds(30), Duration.ofSeconds(3));
        try (var source = new KcpDemoServer(options, sourceServices);
                var target = new KcpDemoServer(options, targetServices);
                var workers = ZeroRuntimeExecutors.localPrototype("kcp-advanced-client", 1);
                var sourceTls = KcpDemoClient.socket(source.port(), source.certificateFile().toPath());
                var targetTls = KcpDemoClient.socket(target.port(), target.certificateFile().toPath())) {
            KcpConnectInfo original = login(sourceTls); login(targetTls);
            var replies = new ArrayBlockingQueue<ProtocolFrame>(4);
            KcpClient first = client(original, workers, replies);
            try {
                first.connect().toCompletableFuture().get(3, TimeUnit.SECONDS);
                exchange(first, replies, 210);
                var oldAddress = first.localAddress();
                first.rebind().toCompletableFuture().get(4, TimeUnit.SECONDS);
                if (oldAddress.equals(first.localAddress())) throw new IllegalStateException("path did not change");
                exchange(first, replies, 211);
                first.quiesce(Duration.ofSeconds(3)).toCompletableFuture().get(4, TimeUnit.SECONDS);
                KcpDemoWire.write(sourceTls, KcpDemoWire.frame(KcpDemoMigration.MIGRATE,
                        KcpDemoMigration.request(original.ticket().conv(), "target")));
                ProtocolFrame handoff = KcpDemoWire.read(sourceTls);
                if (handoff.protocolId() != KcpDemoMigration.MIGRATE) throw new IllegalStateException("missing handoff");
                KcpDemoWire.write(targetTls, KcpDemoWire.frame(KcpDemoMigration.CLAIM, handoff.payload()));
                KcpConnectInfo next = KcpDemoClient.readInfo(targetTls);
                verifyHandoff(original, next);
                first.close().toCompletableFuture().join();
                KcpClient second = client(next, workers, replies);
                try {
                    second.connect().toCompletableFuture().get(3, TimeUnit.SECONDS);
                    exchange(second, replies, 212);
                    second.quiesce(Duration.ofSeconds(3)).toCompletableFuture().get(4, TimeUnit.SECONDS);
                    if (source.server().snapshot().sessions() != 0 || target.server().snapshot().connected() != 1) {
                        throw new IllegalStateException("invalid source/target ownership");
                    }
                    System.out.printf("PASS protection=%s fec=%s rebind=true migration=source->target generation=%d%n",
                            protection, fec, next.ticket().generation());
                } finally { second.close().toCompletableFuture().join(); }
            } finally { first.close().toCompletableFuture().join(); }
        }
    }
    private static KcpOptions options(final String protection, final String fec) {
        int id = switch (protection) { case "HMAC" -> 1; case "CHACHA" -> 2; case "AES" -> 3;
            default -> throw new IllegalArgumentException("unknown protection"); };
        KcpFecOptions coding = switch (fec) { case "NONE" -> KcpFecOptions.none(); case "XOR" -> KcpFecOptions.xor(4);
            case "RS" -> KcpFecOptions.reedSolomon(4, 2); default -> throw new IllegalArgumentException("unknown FEC"); };
        return KcpOptions.builder(KcpProfile.MOBILE)
                .transport(KcpTransportOptions.of(id, coding, KcpPathOptions.validated())).build();
    }
    private static KcpConnectInfo login(final SSLSocket socket) throws Exception {
        KcpDemoWire.write(socket, KcpDemoWire.frame(KcpDemoWire.LOGIN, "demo-secret".getBytes(StandardCharsets.UTF_8)));
        return KcpDemoClient.readInfo(socket);
    }
    private static KcpClient client(final KcpConnectInfo info, final ZeroRuntimeExecutors workers,
            final ArrayBlockingQueue<ProtocolFrame> replies) {
        return new KcpClient(info, workers.logicExecutor(), (connection, frame) -> {
            if (!replies.offer(frame)) throw new IllegalStateException("reply queue full");
            return CompletableFuture.completedFuture(List.of());
        }, new ConnectionListener() { }, null);
    }
    private static void exchange(final KcpClient client, final ArrayBlockingQueue<ProtocolFrame> replies, final int id) throws Exception {
        byte[] payload = new byte[8000]; Arrays.fill(payload, (byte) id);
        client.sendFrame(KcpDemoWire.frame(id, payload)).toCompletableFuture().get(3, TimeUnit.SECONDS);
        ProtocolFrame reply = replies.poll(3, TimeUnit.SECONDS);
        if (reply == null || reply.protocolId() != id || !Arrays.equals(payload, reply.payload())) {
            throw new IllegalStateException("advanced UDP echo mismatch");
        }
    }
    private static void verifyHandoff(final KcpConnectInfo original, final KcpConnectInfo next) {
        if (next.ticket().conv() != original.ticket().conv() || next.ticket().generation() != original.ticket().generation() + 1
                || Arrays.equals(original.ticket().key(), next.ticket().key())) {
            throw new IllegalStateException("handoff must retain conv, fence generation and rotate key");
        }
    }
}
