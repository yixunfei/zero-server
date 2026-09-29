package group.zn.zero.runtime.kcp;

import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.NetworkTuning;
import group.zn.zero.net.kcp.KcpLimits;
import group.zn.zero.net.kcp.KcpOptions;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.net.kcp.KcpTimeouts;
import group.zn.zero.net.kcp.KcpTuning;
import group.zn.zero.net.kcp.KcpTransportOptions;
import group.zn.zero.net.kcp.KcpFecOptions;
import group.zn.zero.net.kcp.KcpPathOptions;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSourceKind;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 一个命名监听器的配置 schema；只有 create 阶段解析组合，不创建线程。 @author zn */
final class KcpRuntimeConfig {
    /** 所属 provider。 */
    private final ComponentId id;
    /** 默认 socket 配置。 */
    private final ServerOptions network;
    /** 默认 KCP 配置。 */
    private final KcpOptions defaults;
    /** 固定键集合。 */
    private final Map<String, ConfigKey<String>> keys = new LinkedHashMap<>();
    KcpRuntimeConfig(final String name, final ServerOptions network, final KcpOptions defaults) {
        this.id = ComponentId.of("zero.kcp." + name); this.network = network; this.defaults = defaults;
        add("profile", defaults.profile().name()); add("host", network.host()); add("port", Integer.toString(network.port()));
        for (String key : new String[]{"mtu", "intervalMillis", "sendWindow", "receiveWindow", "noDelay", "fastResend",
                "minimumRtoMillis", "congestionControl", "flushBatch", "maxSessions", "maxQueuedFrames", "maxPendingSegments",
                "maxFrameBytes", "maxInboundBytesTotal", "bindTimeoutMillis", "heartbeatIntervalMillis", "idleTimeoutMillis",
                "progressTimeoutMillis", "ticketLifetimeMillis", "requireControlTls", "maxPendingBytesPerConnection",
                "maxPendingBytesTotal", "protectionId", "allowUnauthenticated", "maxDatagramBytes", "maxFecBytesTotal",
                "fecWireId", "fecDataShards", "fecParityShards", "fecFlushDelayMillis", "fecExpiryMillis",
                "fecMaxGroups", "fecMaxBytes", "pathEnabled", "pathTimeoutMillis", "pathRetryIntervalMillis",
                "pathRetiredLifetimeMillis", "pathMaxCandidates"}) add(key, "auto");
    }
    private void add(final String name, final String value) {
        String alias = id.value() + "." + name;
        keys.put(name, ConfigKey.string(id, id.value() + "." + name.replaceAll("([A-Z])", ".$1").toLowerCase(Locale.ROOT)).defaultValue(value)
                .alias(ConfigSourceKind.PROGRAMMATIC, alias).alias(ConfigSourceKind.SYSTEM_PROPERTY, alias)
                .alias(ConfigSourceKind.FILE, alias).alias(ConfigSourceKind.REMOTE, alias)
                .alias(ConfigSourceKind.ENVIRONMENT, alias.replace('.', '_').toUpperCase(Locale.ROOT)).build());
    }
    ComponentId id() { return id; }
    ConfigSchema schema() {
        var schema = ConfigSchema.builder(id); keys.values().forEach(schema::add); return schema.build();
    }
    KcpOptions options(final ComponentConfig config) {
        KcpProfile profile = KcpProfile.valueOf(value(config, "profile"));
        KcpOptions base = profile == defaults.profile() ? defaults : profile.options();
        KcpTuning t = base.tuning(); KcpLimits l = base.limits(); KcpTimeouts time = base.timeouts();
        KcpTransportOptions transport = transport(config);
        return KcpOptions.builder(profile)
                .tuning(new KcpTuning(integer(config, "mtu", t.mtu()), integer(config, "intervalMillis", t.intervalMillis()),
                        integer(config, "sendWindow", t.sendWindow()), integer(config, "receiveWindow", t.receiveWindow()),
                        bool(config, "noDelay", t.noDelay()), integer(config, "fastResend", t.fastResend()),
                        integer(config, "minimumRtoMillis", t.minimumRtoMillis()), bool(config, "congestionControl", t.congestionControl()),
                        integer(config, "flushBatch", t.flushBatch())))
                .limits(new KcpLimits(integer(config, "maxSessions", l.maxSessions()), integer(config, "maxQueuedFrames", l.maxQueuedFrames()),
                        integer(config, "maxPendingSegments", l.maxPendingSegments()), integer(config, "maxFrameBytes", l.maxFrameBytes()),
                        number(config, "maxInboundBytesTotal", l.maxInboundBytesTotal())))
                .timeouts(new KcpTimeouts(duration(config, "bindTimeoutMillis", time.bindTimeout()),
                        duration(config, "heartbeatIntervalMillis", time.heartbeatInterval()), duration(config, "idleTimeoutMillis", time.idleTimeout()),
                        duration(config, "progressTimeoutMillis", time.progressTimeout()), duration(config, "ticketLifetimeMillis", time.ticketLifetime())))
                .requireControlTls(bool(config, "requireControlTls", defaults.requireControlTls())).transport(transport).build();
    }
    private KcpTransportOptions transport(final ComponentConfig config) {
        // 业务场景只调整调度/窗口；不能因切换 profile 降级既有安全与路径策略。
        KcpTransportOptions policy = defaults.transport();
        int id = integer(config, "fecWireId", policy.fec().wireId());
        KcpFecOptions fec = id == policy.fec().wireId() ? policy.fec() : switch (id) {
            case 0 -> KcpFecOptions.none();
            case 1 -> KcpFecOptions.xor(4);
            case 2 -> KcpFecOptions.reedSolomon(4, 2);
            default -> policy.fec();
        };
        return new KcpTransportOptions(integer(config, "protectionId", policy.protectionId()),
                bool(config, "allowUnauthenticated", policy.allowUnauthenticated()),
                new KcpFecOptions(id, integer(config, "fecDataShards", fec.dataShards()),
                        integer(config, "fecParityShards", fec.parityShards()),
                        duration(config, "fecFlushDelayMillis", fec.flushDelay()),
                        duration(config, "fecExpiryMillis", fec.expiry()),
                        integer(config, "fecMaxGroups", fec.maxGroups()), number(config, "fecMaxBytes", fec.maxBytes())),
                new KcpPathOptions(bool(config, "pathEnabled", policy.paths().enabled()),
                        duration(config, "pathTimeoutMillis", policy.paths().timeout()),
                        duration(config, "pathRetryIntervalMillis", policy.paths().retryInterval()),
                        duration(config, "pathRetiredLifetimeMillis", policy.paths().retiredPathLifetime()),
                        integer(config, "pathMaxCandidates", policy.paths().maxCandidates())),
                integer(config, "maxDatagramBytes", policy.maxDatagramBytes()),
                number(config, "maxFecBytesTotal", policy.maxFecBytesTotal()));
    }
    ServerOptions network(final ComponentConfig config) {
        NetworkTuning t = network.tuning();
        return new ServerOptions(network.serverType(), value(config, "host"), Integer.parseInt(value(config, "port")),
                network.bossThreads(), network.workerThreads(), network.maxFrameLength(), network.codecType(),
                new NetworkTuning(t.transport(), t.backlog(), t.writeLowWaterMark(), t.writeHighWaterMark(),
                        number(config, "maxPendingBytesPerConnection", t.maxPendingBytesPerConnection()),
                        number(config, "maxPendingBytesTotal", t.maxPendingBytesTotal()), t.flushConsolidationLimit()));
    }
    private String value(final ComponentConfig config, final String key) { return config.require(keys.get(key)); }
    private long number(final ComponentConfig config, final String key, final long fallback) {
        String value = value(config, key);
        try { return value.equals("auto") ? fallback : Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(id + "." + key + " must be an integer", failure); }
    }
    private int integer(final ComponentConfig config, final String key, final int fallback) { return Math.toIntExact(number(config, key, fallback)); }
    private Duration duration(final ComponentConfig config, final String key, final Duration fallback) {
        return Duration.ofMillis(number(config, key, fallback.toMillis()));
    }
    private boolean bool(final ComponentConfig config, final String key, final boolean fallback) {
        String value = value(config, key);
        if (value.equals("auto")) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(id + "." + key + " must be true/false");
        return Boolean.parseBoolean(value);
    }
}
