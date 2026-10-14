package group.zn.zero.examples.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.repository.RepositoryRequest;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.examples.business.generated.dto.DailyRewardClaimDailyRewardProtocolDTO;
import group.zn.zero.examples.business.generated.dto.codec.DailyRewardClaimDailyRewardProtocolDTOCodec;
import group.zn.zero.examples.business.generated.dto.codec.DailyRewardClaimResultProtocolDTOCodec;
import group.zn.zero.examples.business.generated.protocol.ProtocolIds;
import group.zn.zero.game.ActorRequestExecutor;
import group.zn.zero.game.RequestExecution;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.security.AuthenticationProvider.AuthenticationResult;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.starter.LocalRuntime;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 相同类型化 BO 经本地和真实 TCP 完成认证、Actor、聚合 CAS、响应往返。 @author zn */
class DailyRewardFlowTest {
    /** TCP 真实认证后与 local 共用业务；伪造 UID 拒绝，同日重复请求不重复发奖。 */
    @Test void localAndAuthenticatedTcpUseSameTypedBusiness() throws Exception {
        try (var runtime = LocalRuntime.builder(new MapZeroConfig(Map.of()), new InMemoryLogSink(),
                ZeroRuntimeExecutors.localPrototype("reward-test", 2))
                .register(DataRuntime.repositories(Map.of("game", "local")).providers().getFirst())
                .override(DataRuntime.REPOSITORIES, group.zn.zero.runtime.api.ComponentId.of("zero.data.repository-catalog"))
                .require(DataRuntime.REPOSITORIES).build()) {
            runtime.start();
            var repository = runtime.require(DataRuntime.REPOSITORIES).create(new RepositoryRequest<>("game", DailyRewardRepository.DEFINITION));
            var service = new DailyRewardService(repository, new InMemoryCacheService<>(),
                    new InMemoryEventBus(new InMemoryDeadLetterSink(), Clock.systemUTC(), Runnable::run), Clock.systemUTC());
            try (var actors = new ActorRequestExecutor(runtime.require(ActorRuntime.ACTOR_SCHEDULER))) {
                var flow = new DailyRewardFlow(new DailyRewardBO(actors, service));
                var first = flow.handle(request(1001), scope(1001)).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals("ACCEPTED", responseStatus(first));
                byte[] token = new byte[32]; new java.security.SecureRandom().nextBytes(token);
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token));
                var chain = new SecurityChain(auth -> CompletableFuture.completedFuture(
                        digest.equals(auth.credentialReference()) ? AuthenticationResult.accepted(identity(1001))
                                : AuthenticationResult.rejected()),
                        replay -> CompletableFuture.completedFuture(ReplayProtection.ReplayDecision.ACCEPTED), null, false);
                try (var server = new DailyRewardTcpServer(flow, chain, runtime.require(RuntimeBasics.EXECUTORS))) {
                    server.start();
                    int port = Integer.parseInt(server.bindAddress().substring(server.bindAddress().lastIndexOf(':') + 1));
                    try (var socket = new Socket("127.0.0.1", port)) {
                        socket.setSoTimeout(5000);
                        send(socket, new ProtocolFrame(1, 1, 0, null, token));
                        assertEquals(1, receive(socket).protocolId());
                        send(socket, request(1001));
                        assertEquals("DUPLICATE", responseStatus(receive(socket)));
                        send(socket, request(1002));
                        ProtocolFrame rejected = receive(socket);
                        assertTrue(new String(rejected.payload(), StandardCharsets.UTF_8).contains("ZERO-INVALID-ARGUMENT"));
                    }
                }
                assertEquals(100, repository.findById(1001L).toCompletableFuture().join().orElseThrow().quantity());
                assertTrue(repository.findById(1002L).toCompletableFuture().join().isEmpty());
            }
        }
    }

    static RequestExecution scope(final long uid) {
        return SecurityContextBridge.with(identity(uid), () -> RequestExecution.capture(Duration.ofSeconds(3)));
    }

    private static SecurityContext identity(final long uid) {
        Instant now = Instant.now();
        return new SecurityContext(Long.toString(uid), now, now.plusSeconds(60), "test", "local", "local",
                "trusted-trace", "session-" + uid, Set.of("reward:claim", "network.request"), Map.of());
    }

    static ProtocolFrame request(final long uid) {
        var request = new DailyRewardClaimDailyRewardProtocolDTO();
        request.uid = uid; request.idempotencyKey = "daily"; request.itemId = "coin"; request.quantity = 100;
        request.traceId = "untrusted-dto-trace";
        try (var writer = new ZeroWriter()) {
            DailyRewardClaimDailyRewardProtocolDTOCodec.INSTANCE.write(writer, request);
            return new ProtocolFrame(ProtocolIds.DAILY_REWARD_CLAIM_DAILY_REWARD_PROTOCOL, 1, 0, null, writer.toByteArray());
        }
    }

    static String responseStatus(final ProtocolFrame response) {
        assertEquals(ProtocolIds.DAILY_REWARD_CLAIM_RESULT_PROTOCOL, response.protocolId());
        var dto = DailyRewardClaimResultProtocolDTOCodec.INSTANCE.read(response.payloadReader());
        assertEquals("trusted-trace", dto.traceId);
        return dto.status;
    }

    private static void send(final Socket socket, final ProtocolFrame frame) throws Exception {
        byte[] bytes = new ZeroBinaryFrameCodec().encode(frame);
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }

    private static ProtocolFrame receive(final Socket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        if (length <= 0 || length > 1_048_576) throw new IllegalStateException("invalid frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IllegalStateException("truncated frame");
        return new ZeroBinaryFrameCodec().decode(bytes);
    }
}
