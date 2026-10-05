package group.zn.zero.runtime.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.runtime.rpc.RpcRuntime;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.starter.production.ZeroProductionRuntimeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** 相同生产配置的两个调用方必须独占 reply topic，成功不写结构化传输日志。 @author zn */
class KafkaReplyIsolationExternalIT {

    /** 每个调用方发送数量，验证同批请求独立完成。 */
    private static final int CALLS = 8;
    /** 失败异常的业务哨兵，不得进入安全日志。 */
    private static final String SECRET = "reply-test-business-secret";

    /** 真实 broker 往返、计数、topic 隔离和失败日志；只删除本测试创建的 topic。 */
    @Test
    void productionCallersRouteRepliesIndependentlyWithoutSuccessLogs() throws Exception {
        assertTrue(Boolean.getBoolean("zero.external.tests"));
        String bootstrap = System.getenv("ZERO_KAFKA_BOOTSTRAP_SERVERS");
        assertTrue(bootstrap != null && !bootstrap.isBlank(), "Kafka bootstrap environment is required");
        String prefix = "perf.reply." + UUID.randomUUID().toString().replace("-", "");
        String requestTopic = prefix + ".request.echo";
        List<ZeroLogRecord> logs = new CopyOnWriteArrayList<>();
        List<String> ownedTopics = new ArrayList<>();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            try (var first = runtime(bootstrap, prefix, logs); var second = runtime(bootstrap, prefix, logs)) {
                var firstAdapter = (KafkaRpcLifecycleAdapter) first.require(RpcRuntime.RPC_TRANSPORT);
                var secondAdapter = (KafkaRpcLifecycleAdapter) second.require(RpcRuntime.RPC_TRANSPORT);
                String firstReply = firstAdapter.settings().replyTopic();
                String secondReply = secondAdapter.settings().replyTopic();
                assertNotEquals(firstReply, secondReply);
                ownedTopics.addAll(List.of(requestTopic, firstReply, secondReply));
                admin.createTopics(ownedTopics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)
                        .configs(Map.of("retention.ms", "60000"))).toList()).all().get(20, TimeUnit.SECONDS);
                List<String> deliveredReplyTopics = new CopyOnWriteArrayList<>();
                firstAdapter.register("echo", "copy", requestTopic, prefix + ".service", RpcHandler.sync(request -> {
                    deliveredReplyTopics.add(request.replyTopic());
                    return new RpcResponse(request.correlationId(), request.traceId(), SystemErrorCode.OK, request.payload());
                }));
                first.start();
                second.start();
                var assertion = SecurityMetadataAssertion.digest("local-test-key".getBytes(StandardCharsets.UTF_8));
                firstAdapter.delegate().orElseThrow().securityMetadataVerifier(SecurityMetadataAssertion.verifier(assertion));
                verifySuccess(firstAdapter, secondAdapter, prefix, assertion, admin, deliveredReplyTopics, logs);
                firstAdapter.register("echo", "fail", requestTopic, prefix + ".service", RpcHandler.sync(request -> {
                    throw new IllegalStateException(SECRET);
                }));
                var failure = secondAdapter.request(request(prefix, "fail", "failure", assertion))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(RpcErrorCode.HANDLER_FAILED.code(), failure.errorCode().code());
                await(() -> logs.stream().anyMatch(record -> record.operation().equals("kafka-rpc-handler_failed")));
                assertFalse(logs.toString().contains(SECRET));
            } finally {
                if (!ownedTopics.isEmpty()) {
                    admin.deleteTopics(ownedTopics).all().get(20, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** 两个调用方各完成 CALLS 次，broker 每个独占 topic 只写入对应调用方的响应。 */
    private void verifySuccess(final KafkaRpcLifecycleAdapter first, final KafkaRpcLifecycleAdapter second,
            final String prefix, final SecurityMetadataAssertion assertion, final AdminClient admin,
            final List<String> replyTopics, final List<ZeroLogRecord> logs) throws Exception {
        List<CompletionStage<RpcResponse>> requests = new ArrayList<>();
        for (int index = 0; index < CALLS; index++) {
            requests.add(first.request(request(prefix, "copy", "first-" + index, assertion)));
            requests.add(second.request(request(prefix, "copy", "second-" + index, assertion)));
        }
        for (var request : requests) {
            RpcResponse response = request.toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(SystemErrorCode.OK.code(), response.errorCode().code());
            assertArrayEquals(response.correlationId().getBytes(StandardCharsets.UTF_8), response.payload());
        }
        await(() -> first.delegate().orElseThrow().snapshot().responseSentCount() == CALLS * 2);
        for (var adapter : List.of(first, second)) {
            var snapshot = adapter.delegate().orElseThrow().snapshot();
            assertEquals(CALLS, snapshot.requestSentCount());
            assertEquals(Integer.toString(CALLS), snapshot.attributes().get("pendingCompletedCount"));
            assertEquals(0, snapshot.pendingSize());
            assertEquals(0, snapshot.pendingTimeoutCount());
            assertEquals(CALLS, replyTopics.stream().filter(adapter.settings().replyTopic()::equals).count());
            var partition = new TopicPartition(adapter.settings().replyTopic(), 0);
            long records = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all()
                    .get(20, TimeUnit.SECONDS).get(partition).offset();
            assertEquals(CALLS, records);
        }
        assertEquals(CALLS * 2, first.delegate().orElseThrow().snapshot().requestReceivedCount());
        assertTrue(logs.stream().noneMatch(record -> record.operation().startsWith("kafka-rpc-")));
    }

    /** 使用相同生产配置构建两个 runtime；日志仍通过 production 安全 pipeline。 */
    private ZeroProductionRuntime runtime(final String bootstrap, final String prefix, final List<ZeroLogRecord> logs) {
        Map<String, String> config = Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS, bootstrap,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, prefix + ".client",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, prefix + ".group",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, prefix,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, prefix + ".reply");
        return ZeroProductionRuntimeFactory.productionBuilder(new MapZeroConfig(config))
                .configSourceLookups(key -> null, key -> null).terminalLogSink(logs::add).build();
    }

    /** 故意传入共享 reply 配置值，验证 lifecycle 会实际替换；安全快照逐请求签名。 */
    private RpcRequest request(final String prefix, final String method, final String id,
            final SecurityMetadataAssertion assertion) {
        Instant now = Instant.now();
        var context = new SecurityContext("test-caller", now, now.plusSeconds(120), "kafka", "peer", "trusted",
                "trace-" + id, id, Set.of("rpc.invoke"), Map.of());
        return new RpcRequest(id, prefix + ".reply", "echo", method, context.traceId(), now.plusSeconds(30),
                RpcMode.REQUEST_RESPONSE, prefix + ".request.echo", prefix + ".service", id,
                SecurityMetadataAssertion.signed(context, "local-assertion", assertion),
                id.getBytes(StandardCharsets.UTF_8));
    }

    /** 有限等待发送回调和观测完成，不假设 consumer 线程的调度时机。 */
    private void await(final BooleanSupplier completed) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertTrue(completed.getAsBoolean(), "Kafka completion callback timed out");
    }
}
