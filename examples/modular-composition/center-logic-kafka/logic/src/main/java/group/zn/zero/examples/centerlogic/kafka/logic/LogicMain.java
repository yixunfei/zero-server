package group.zn.zero.examples.centerlogic.kafka.logic;

import group.zn.zero.examples.centerlogic.kafka.common.*;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Logic JVM: calls center over Kafka and performs an explicit drain/unregister sequence. */
public final class LogicMain {
    private LogicMain() { }
    /** Registers, invokes, records metadata and unregisters, then exits. */
    public static void main(final String[] args) throws Exception {
        String bootstrap = required(args, 0, "bootstrap");
        String prefix = required(args, 1, "topicPrefix");
        Path control = Path.of(required(args, 2, "controlDir"));
        String suffix = suffix(prefix);
        KafkaRpcSettings settings = new KafkaRpcSettings(bootstrap, "logic-" + suffix, "logic-group-" + suffix,
                prefix, "reply-logic-" + suffix, 128, Duration.ofMillis(100), Duration.ofSeconds(5), Map.of(), Map.of("auto.offset.reset", "earliest"));
        RpcCodecRegistry codecs = new RpcCodecRegistry();
        CenterContractCodecs.register(codecs);
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(settings)) {
            CenterContract client = new RpcClientFactory(adapter, codecs,
                    RpcCallOptions.defaults().withReplyTopic(settings.replyTopic()).withTraceId("trace-logic-" + suffix)).create(CenterContract.class);
            CenterRegistration registration = new CenterRegistration("logic-" + suffix, "logic", "trace-register-" + suffix);
            RpcResult<CenterAck> registered = client.register(registration);
            if (!registered.success()) throw new IllegalStateException("register failed");
            Files.writeString(control.resolve("logic.ready"), "REGISTERED|instanceId=" + registration.instanceId());
            RpcResult<CenterAck> heartbeat = client.heartbeat(new CenterHeartbeat(registration.instanceId(), "trace-heartbeat-" + suffix, System.currentTimeMillis()));
            if (!heartbeat.success()) throw new IllegalStateException("heartbeat failed");
            Files.writeString(control.resolve("logic.requested"), "correlation-visible|traceId=" + heartbeat.result().traceId() + "|timeoutAt=method-default");
            Files.writeString(control.resolve("logic.drain"), "DRAINING|inFlight=0");
            RpcResult<CenterAck> removed = client.unregister(registration);
            if (!removed.success()) throw new IllegalStateException("unregister failed");
            Files.writeString(control.resolve("logic.stopped"), "UNREGISTERED|resources=closed");
        }
    }
    private static String required(String[] args, int index, String name) { if (args.length <= index || args[index].isBlank()) throw new IllegalArgumentException(name + " required"); return args[index]; }
    private static String suffix(String value) { return value.replaceAll("[^A-Za-z0-9]", "").substring(Math.max(0, value.replaceAll("[^A-Za-z0-9]", "").length() - 10)); }
}
