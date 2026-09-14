package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.RuntimeProviderCapability;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allowlisted component code expressions, with dependencies resolved from the shared provider model. */
public final class ScaffoldComponents {
    private static final Map<String, Spec> CATALOG = catalog();
    private final RuntimeCapabilityModel model;

    public ScaffoldComponents(final RuntimeCapabilityModel model) {
        this.model = java.util.Objects.requireNonNull(model, "model");
    }

    public static Set<String> supported() {
        return CATALOG.keySet();
    }

    public Selection resolve(final List<String> requiredCapabilities, final List<String> requested) {
        Set<String> selected = new LinkedHashSet<>();
        selected.add("bootstrap");
        requiredCapabilities.forEach(capability -> selected.add(defaultComponent(capability)));
        requested.forEach(id -> {
            if (!CATALOG.containsKey(id)) {
                throw new IllegalArgumentException("unsupported component: " + id);
            }
            selected.add(id);
        });
        // 真实实现替换同一能力的本地组合，避免生成冗余默认 provider 和依赖。
        if (selected.contains("kafka")) {
            selected.remove("rpc");
        }
        if (selected.contains("nacos")) {
            selected.remove("discovery");
        }
        if (selected.contains("custom-actor")) {
            selected.add("actor");
        }
        boolean changed;
        do {
            changed = false;
            for (String id : List.copyOf(selected)) {
                for (RuntimeProviderCapability provider : providers(id)) {
                    List<String> requirements = new ArrayList<>(provider.requires());
                    model.closure(provider.provides()).forEach(capability -> requirements.addAll(capability.requires()));
                    for (String requirement : requirements) {
                        if (!provided(selected).contains(requirement)) {
                            changed |= selected.add(defaultComponent(requirement));
                        }
                    }
                }
            }
        } while (changed);
        List<String> ids = CATALOG.keySet().stream().filter(selected::contains).toList();
        List<MavenCoordinate> artifacts = ids.stream().flatMap(id -> {
            if (id.equals("discovery")) {
                return java.util.stream.Stream.of(MavenCoordinate.zero("zero-runtime-discovery"));
            }
            return providers(id).stream().flatMap(provider -> provider.artifacts().stream());
        }).distinct().sorted().toList();
        List<String> providerIds = new ArrayList<>(ids.stream().flatMap(id -> providers(id).stream())
                .map(provider -> provider.providerId().value()).distinct().sorted().toList());
        if (ids.contains("custom-actor")) {
            providerIds.remove(StandardRuntimeCapabilityModel.LOCAL_ACTOR.value());
            providerIds.add("application.actor");
        }
        if (ids.contains("discovery")) {
            providerIds.add("zero.discovery.local");
            providerIds.add("zero.discovery.rpc-resolver");
        }
        Set<String> capabilities = provided(selected);
        if (!repositorySources(ids).isEmpty()) {
            providerIds.add("zero.data.repository-catalog");
            capabilities.add(StandardRuntimeCapabilityModel.REPOSITORIES);
        }
        return new Selection(ids, artifacts, capabilities.stream().sorted().toList(),
                List.copyOf(providerIds), ids.stream().anyMatch(this::external));
    }

    /** 返回按选择顺序排列的 Repository 来源；不可变、可为空、线程安全。 */
    static List<String> repositorySources(final List<String> ids) {
        return ids.stream().filter(id -> List.of("data", "redis", "mongo", "postgresql").contains(id))
                .map(id -> id.equals("data") ? "local" : id).toList();
    }

    /** 判断组件是否只支持外部运行档位；只读，不创建资源。 */
    private boolean external(final String id) {
        return providers(id).stream().anyMatch(provider -> !provider.profiles().contains("local"));
    }

    private Set<String> provided(final Set<String> selected) {
        Set<String> result = new LinkedHashSet<>();
        selected.forEach(id -> providers(id).forEach(provider -> result.addAll(provider.provides())));
        if (selected.contains("discovery")) {
            result.add(StandardRuntimeCapabilityModel.SERVICE_DISCOVERY);
            result.add(StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER);
        }
        return result;
    }

    private String defaultComponent(final String capability) {
        for (String id : CATALOG.keySet()) {
            if (!external(id) && providers(id).stream().anyMatch(provider -> provider.provides().contains(capability))) {
                return id;
            }
        }
        if (capability.equals(StandardRuntimeCapabilityModel.SERVICE_DISCOVERY)
                || capability.equals(StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER)) {
            return "discovery";
        }
        throw new IllegalArgumentException("no scaffold default provider for capability: " + capability);
    }

    private List<RuntimeProviderCapability> providers(final String id) {
        return CATALOG.get(id).providers().stream().map(provider -> model.provider(provider).orElseThrow()).toList();
    }

    static String installExpression(final String id) {
        return CATALOG.get(id).expression();
    }

    private static Map<String, Spec> catalog() {
        var specs = new LinkedHashMap<String, Spec>();
        add(specs, "bootstrap", "", StandardRuntimeCapabilityModel.LOCAL_CONFIG, StandardRuntimeCapabilityModel.LOCAL_EXECUTORS);
        add(specs, "actor", "actor.ActorRuntime", StandardRuntimeCapabilityModel.LOCAL_ACTOR);
        add(specs, "event", "event.EventRuntime", StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER, StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS);
        add(specs, "protocol", "protocol.ProtocolRuntime", StandardRuntimeCapabilityModel.LOCAL_PROTOCOL);
        add(specs, "rpc", "rpc.RpcRuntime", StandardRuntimeCapabilityModel.LOCAL_RPC);
        add(specs, "data", "data.DataRuntime", StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE, StandardRuntimeCapabilityModel.LOCAL_REPOSITORIES);
        add(specs, "cache", "cache.CacheRuntime", StandardRuntimeCapabilityModel.LOCAL_CACHE);
        add(specs, "log", "log.LogRuntime", StandardRuntimeCapabilityModel.LOCAL_LOG_SINK, StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER);
        add(specs, "monitor", "monitor.MonitorRuntimeComponent", StandardRuntimeCapabilityModel.LOCAL_MONITOR);
        add(specs, "discovery", "discovery.DiscoveryRuntime");
        add(specs, "redis", "redis.RedisRuntime", StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE, StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA);
        add(specs, "kafka", "kafka.KafkaRuntime", StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC);
        add(specs, "nacos", "nacos.NacosRuntime", StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY,
                StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER);
        add(specs, "mongo", "mongo.MongoRuntime", StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA);
        add(specs, "postgresql", "postgresql.PostgresqlRuntime", StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA);
        add(specs, "custom-actor", "");
        return java.util.Collections.unmodifiableMap(specs);
    }

    private static void add(final Map<String, Spec> specs, final String id, final String type, final ComponentId... providers) {
        specs.put(id, new Spec(List.of(providers), type.isEmpty() ? "" : "group.zn.zero.runtime." + type + ".module()"));
    }

    private record Spec(List<ComponentId> providers, String expression) { }

    public record Selection(List<String> components, List<MavenCoordinate> artifacts,
                            List<String> capabilities, List<String> providers, boolean external) { }
}
