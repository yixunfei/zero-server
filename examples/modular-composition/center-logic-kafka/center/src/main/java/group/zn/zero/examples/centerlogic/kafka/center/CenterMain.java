package group.zn.zero.examples.centerlogic.kafka.center;

import group.zn.zero.examples.centerlogic.kafka.common.*;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.server.RpcBoundService;
import group.zn.zero.rpc.server.RpcServiceBinder;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.rpc.RpcRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Center JVM: owns registration state and exposes the Kafka RPC contract. */
public final class CenterMain {
    private CenterMain() { }

    /** Starts center until a stop file appears. */
    public static void main(final String[] args) throws Exception {
        String bootstrap = required(args, 0, "bootstrap");
        String prefix = required(args, 1, "topicPrefix");
        Path control = Path.of(required(args, 2, "controlDir"));
        Files.createDirectories(control);
        String group = "center-group-" + suffix(prefix);
        KafkaRpcSettings settings = settings(bootstrap, "center-" + suffix(prefix), group, prefix, "reply-center-" + suffix(prefix));
        RpcCodecRegistry codecs = new RpcCodecRegistry();
        CenterContractCodecs.register(codecs);
        AtomicBoolean draining = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        try (var runtime = RuntimeBasics.builder().install(RpcRuntime.module()).build();
             KafkaRpcAdapter adapter = new KafkaRpcAdapter(settings)) {
            CenterService service = new CenterService(draining, active, requests);
            RpcBoundService binding = new RpcServiceBinder(adapter, codecs).bind(CenterContract.class, service);
            runtime.start();
            Files.writeString(control.resolve("center.ready"), "READY");
            while (!Files.exists(control.resolve("center.stop"))) {
                if (Files.exists(control.resolve("center.drain"))) draining.set(true);
                Thread.sleep(50L);
            }
            Files.writeString(control.resolve("center.drained"), "active=" + service.activeCount() + "|requests=" + requests.get());
            binding.close();
        }
        Files.writeString(control.resolve("center.stopped"), "STOPPED");
    }

    private static KafkaRpcSettings settings(String bootstrap, String client, String group, String prefix, String reply) {
        return new KafkaRpcSettings(bootstrap, client, group, prefix, reply, 128, Duration.ofMillis(100), Duration.ofSeconds(5), Map.of(), Map.of("auto.offset.reset", "earliest"));
    }
    private static String suffix(String value) { return value.replaceAll("[^A-Za-z0-9]", "").substring(Math.max(0, value.replaceAll("[^A-Za-z0-9]", "").length() - 10)); }
    private static String required(String[] args, int index, String name) { if (args.length <= index || args[index].isBlank()) throw new IllegalArgumentException(name + " required"); return args[index]; }

    static final class CenterService implements CenterContract {
        private final Set<String> instances = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean draining;
        private final AtomicInteger requests;
        CenterService(AtomicBoolean draining, AtomicInteger active, AtomicInteger requests) {
            this.draining = draining;
            this.requests = requests;
        }
        public RpcResult<CenterAck> register(CenterRegistration request) {
            if (draining.get()) return RpcResult.failure(group.zn.zero.rpc.error.RpcErrorCode.SERVICE_NOT_FOUND, "center draining");
            instances.add(request.instanceId());
            return acknowledge(request.instanceId(), request.traceId(), "REGISTERED");
        }
        public RpcResult<CenterAck> heartbeat(CenterHeartbeat request) {
            if (!instances.contains(request.instanceId())) return RpcResult.failure(group.zn.zero.rpc.error.RpcErrorCode.SERVICE_NOT_FOUND, "instance not registered");
            return acknowledge(request.instanceId(), request.traceId(), draining.get() ? "DRAINING" : "HEARTBEAT");
        }
        public RpcResult<CenterAck> unregister(CenterRegistration request) {
            instances.remove(request.instanceId());
            return acknowledge(request.instanceId(), request.traceId(), "UNREGISTERED");
        }
        int activeCount() { return instances.size(); }

        private RpcResult<CenterAck> acknowledge(String id, String trace, String state) {
            requests.incrementAndGet();
            return RpcResult.success(new CenterAck(id, state, trace));
        }
    }
}
