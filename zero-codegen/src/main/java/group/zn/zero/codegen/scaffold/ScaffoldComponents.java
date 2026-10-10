package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.RuntimeProviderCapability;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 基于显式元数据目录解析脚手架组件、Provider、依赖和装配表达式。 */
public final class ScaffoldComponents {
    /** 内置目录；固定顺序、不可变。 */
    private static final List<ScaffoldComponentDefinition> STANDARD_DEFINITIONS = createStandardDefinitions();
    /** Provider 的能力与依赖声明。 */
    private final RuntimeCapabilityModel model;
    /** 按注册顺序冻结的组件定义。 */
    private final Map<String, ScaffoldComponentDefinition> catalog;
    /** 显式目录身份，与 schema 版本分别记录。 */
    private final String catalogId;

    /**
     * 使用标准组件目录创建解析器；目录固定且可跨线程读取。
     * @param model Provider 能力模型；不可为空。
     * @throws NullPointerException 能力模型为空时抛出。
     */
    public ScaffoldComponents(final RuntimeCapabilityModel model) {
        this("zero.scaffold.standard", model, STANDARD_DEFINITIONS);
    }

    /**
     * 冻结应用显式目录；不创建 Provider、不修改输入，可跨线程使用。
     * @param model Provider 能力模型；不可为空。
     * @param definitions 有序定义；必须包含 bootstrap，不可为空。
     * @throws NullPointerException 输入或条目为空时抛出。
     * @throws IllegalArgumentException 组件 ID、Provider 重复或未知、缺少 bootstrap 时抛出。
     */
    public ScaffoldComponents(
            final RuntimeCapabilityModel model,
            final Collection<ScaffoldComponentDefinition> definitions) {
        this(Objects.requireNonNull(model, "model").id(), model, definitions);
    }

    /**
     * 创建命名目录；不扫描 classpath，不创建资源，构造后线程安全。
     * @param catalogId 稳定目录 ID。
     * @param model 能力模型。
     * @param definitions 显式组件定义。
     * @throws IllegalArgumentException ID、引用或唯一性校验失败时抛出。
     * @throws NullPointerException 输入为空时抛出。
     */
    public ScaffoldComponents(final String catalogId, final RuntimeCapabilityModel model,
            final Collection<ScaffoldComponentDefinition> definitions) {
        this.catalogId = ComponentId.of(catalogId).value();
        this.model = Objects.requireNonNull(model, "model");
        Map<String, ScaffoldComponentDefinition> entries = new LinkedHashMap<>();
        Set<ComponentId> providers = new LinkedHashSet<>();
        for (ScaffoldComponentDefinition definition : Objects.requireNonNull(definitions, "definitions")) {
            ScaffoldComponentDefinition checked = Objects.requireNonNull(definition, "definition");
            if (entries.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("duplicate scaffold component: " + checked.id());
            }
            if (!checked.id().equals("bootstrap") && !checked.providers().isEmpty() && checked.installExpression().isBlank()) {
                throw new IllegalArgumentException("provider component requires an install expression: " + checked.id());
            }
            checked.providers().forEach(provider -> {
                if (!providers.add(provider)) {
                    throw new IllegalArgumentException("provider is registered by multiple components: " + provider);
                }
                model.provider(provider).orElseThrow(() ->
                        new IllegalArgumentException("unknown scaffold provider: " + provider));
            });
        }
        if (!entries.containsKey("bootstrap")) {
            throw new IllegalArgumentException("scaffold catalog must contain bootstrap");
        }
        catalog = Collections.unmodifiableMap(entries);
    }

    /** 返回目录身份；不可变字符串，无副作用且线程安全。@return 稳定目录 ID。 */
    public String catalogId() {
        return catalogId;
    }

    /** 返回所有显式组件 ID；无副作用且线程安全。@return 有序、不可变、非空列表。 */
    public List<String> componentIds() {
        return List.copyOf(catalog.keySet());
    }

    /** 返回标准目录中的组件 ID；有序、不可变、线程安全。 */
    public static Set<String> supported() {
        Set<String> ids = STANDARD_DEFINITIONS.stream()
                .map(ScaffoldComponentDefinition::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(ids);
    }

    /**
     * 返回内置目录，供应用复制后追加或替换定义；无副作用。
     * @return 不可变、有序、非空且线程安全的定义列表。
     */
    public static List<ScaffoldComponentDefinition> standardDefinitions() {
        return STANDARD_DEFINITIONS;
    }

    /**
     * 根据模板能力和显式组件解析依赖闭包；不会创建 Provider 或写入文件。
     * @param requiredCapabilities 模板要求的能力；不可为空。
     * @param requested 用户显式选择的组件 ID；不可为空。
     * @return 有序、不可变的组件选择结果。
     * @throws NullPointerException 参数为空时抛出。
     * @throws IllegalArgumentException 组件未知或能力没有可用 Provider 时抛出。
     */
    public Selection resolve(final List<String> requiredCapabilities, final List<String> requested) {
        Set<String> selected = new LinkedHashSet<>();
        selected.add("bootstrap");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities")
                .forEach(capability -> selected.add(defaultComponent(capability)));
        Objects.requireNonNull(requested, "requested").forEach(id -> {
            if (!catalog.containsKey(id)) {
                throw new IllegalArgumentException("unsupported component: " + id);
            }
            selected.add(id);
        });
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
        List<String> ids = catalog.keySet().stream().filter(selected::contains).toList();
        validateSelection(ids);
        List<MavenCoordinate> artifacts = ids.stream().flatMap(id -> artifacts(id).stream())
                .distinct().sorted().toList();
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
        Map<String, ScaffoldComponentDefinition> selectedDefinitions = new LinkedHashMap<>();
        ids.forEach(id -> selectedDefinitions.put(id, catalog.get(id)));
        return new Selection(ids, artifacts, capabilities.stream().sorted().toList(),
                List.copyOf(providerIds), ids.stream().anyMatch(this::external), selectedDefinitions);
    }

    /** 返回按选择顺序排列的 Repository 来源；不可变、可为空、线程安全。 */
    static List<String> repositorySources(final List<String> ids) {
        return ids.stream().filter(id -> List.of("data", "redis", "mongo", "postgresql").contains(id))
                .map(id -> id.equals("data") ? "local" : id).toList();
    }

    /** 返回组件安装表达式；未知 ID 直接失败。 */
    static String installExpression(final Selection selection, final String id) {
        return selection.definition(id).installExpression();
    }

    private List<MavenCoordinate> artifacts(final String id) {
        ScaffoldComponentDefinition definition = catalog.get(id);
        return java.util.stream.Stream.concat(definition.artifacts().stream(),
                model.artifactsForProviders(definition.providers()).stream()).toList();
    }

    /** 单值能力只能有一个 Provider；profile 不满足时在写盘前失败。 */
    private void validateSelection(final List<String> ids) {
        String profile = ids.stream().anyMatch(this::external) ? "external-test" : "local";
        Map<String, ComponentId> singles = new LinkedHashMap<>();
        for (String id : ids) {
            for (RuntimeProviderCapability provider : providers(id)) {
                if (!provider.profiles().contains(profile)) {
                    throw new IllegalArgumentException("provider does not support profile " + profile + ": " + provider.providerId());
                }
                for (String capability : provider.provides()) {
                    var definition = model.capability(capability).orElseThrow();
                    if (!definition.profiles().contains(profile)) {
                        throw new IllegalArgumentException("capability does not support profile " + profile + ": " + capability);
                    }
                    if (definition.cardinality()
                            == group.zn.zero.runtime.api.BindingCardinality.SINGLE
                            && singles.putIfAbsent(capability, provider.providerId()) != null) {
                        throw new IllegalArgumentException("conflicting providers for capability: " + capability);
                    }
                }
            }
        }
    }

    private boolean external(final String id) {
        return providers(id).stream().anyMatch(provider -> !provider.profiles().contains("local"));
    }

    /** 外部档位与外部服务分开判定；内置网络生命周期只提供本进程策略，不连接中间件。 */
    boolean requiresExternalServices(final Selection selection) {
        return selection.components().stream().flatMap(id -> providers(id).stream())
                .anyMatch(provider -> !provider.profiles().contains("local")
                        && !provider.providerId().equals(StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE));
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
        for (String id : catalog.keySet()) {
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
        ScaffoldComponentDefinition definition = catalog.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("unknown scaffold component: " + id);
        }
        return definition.providers().stream().map(provider -> model.provider(provider).orElseThrow()).toList();
    }

    private static List<ScaffoldComponentDefinition> createStandardDefinitions() {
        List<ScaffoldComponentDefinition> definitions = new ArrayList<>();
        add(definitions, "bootstrap", "", StandardRuntimeCapabilityModel.LOCAL_CONFIG,
                StandardRuntimeCapabilityModel.LOCAL_EXECUTORS);
        add(definitions, "actor", "actor.ActorRuntime", StandardRuntimeCapabilityModel.LOCAL_ACTOR);
        add(definitions, "event", "event.EventRuntime", StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER,
                StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS);
        add(definitions, "protocol", "protocol.ProtocolRuntime", StandardRuntimeCapabilityModel.LOCAL_PROTOCOL);
        add(definitions, "rpc", "rpc.RpcRuntime", StandardRuntimeCapabilityModel.LOCAL_RPC);
        add(definitions, "data", "data.DataRuntime", StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE,
                StandardRuntimeCapabilityModel.LOCAL_REPOSITORIES);
        add(definitions, "cache", "cache.CacheRuntime", StandardRuntimeCapabilityModel.LOCAL_CACHE);
        add(definitions, "log", "log.LogRuntime", StandardRuntimeCapabilityModel.LOCAL_LOG_SINK,
                StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER);
        add(definitions, "monitor", "monitor.MonitorRuntimeComponent", StandardRuntimeCapabilityModel.LOCAL_MONITOR);
        add(definitions, "discovery", "discovery.DiscoveryRuntime", List.of(), List.of(
                MavenCoordinate.zero("zero-runtime-discovery")), Map.of(), "");
        add(definitions, "redis", "redis.RedisRuntime", List.of(StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE,
                StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA), List.of(), Map.of("zero.adapter.data.redis.enabled", "true"),
                "zero.redis.uri=redis://127.0.0.1:6379\n");
        add(definitions, "kafka", "kafka.KafkaRuntime", List.of(StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC), List.of(),
                Map.of("zero.adapter.rpc.kafka.enabled", "true"),
                "zero.rpc.kafka.bootstrap-servers=127.0.0.1:9092\n# Each process needs its own client ID, consumer group and reply topic.\n"
                        + "zero.rpc.kafka.client-id=${projectName}\nzero.rpc.kafka.consumer-group-id=${projectName}\n"
                        + "zero.rpc.kafka.topic-prefix=game\nzero.rpc.kafka.reply-topic=game.reply.${projectName}\n");
        add(definitions, "nacos", "nacos.NacosRuntime", List.of(StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY,
                StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER), List.of(), Map.of("zero.discovery.mode", "nacos"),
                "zero.discovery.nacos.server-addr=127.0.0.1:8848\nzero.discovery.nacos.namespace=game\n"
                        + "zero.discovery.nacos.default-group=DEFAULT_GROUP\nzero.discovery.nacos.default-cluster=DEFAULT\n");
        add(definitions, "mongo", "mongo.MongoRuntime", List.of(StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA), List.of(),
                Map.of("zero.adapter.data.mongo.enabled", "true"),
                "zero.mongo.uri=mongodb://127.0.0.1:27017\nzero.mongo.database=game\n");
        add(definitions, "postgresql", "postgresql.PostgresqlRuntime",
                List.of(StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA), List.of(),
                Map.of("zero.adapter.data.postgresql.enabled", "true"),
                "zero.postgresql.url=jdbc:postgresql://127.0.0.1:5432/game\nzero.postgresql.username=\n"
                        + "zero.postgresql.password=\nzero.postgresql.table=zero_data_object\n");
        add(definitions, "custom-actor", "", List.of(), List.of(), Map.of(), "");
        add(definitions, "net", "group.zn.zero.runtime.net.NetworkRuntime.module("
                        + "(connection, frame) -> group.zn.zero.net.lifecycle.NetworkAdmissionDecision.rejected("
                        + "group.zn.zero.net.error.NetErrorCode.HANDSHAKE_REJECTED, "
                        + "group.zn.zero.net.lifecycle.ConnectionRejectionReason.HANDSHAKE_REJECTED), null)",
                List.of(StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE), List.of(
                        MavenCoordinate.zero("zero-runtime-net"), MavenCoordinate.zero("zero-net")),
                Map.of("zero.net.lifecycle.enabled", "true"),
                "zero.net.host=127.0.0.1\nzero.net.port=0\nzero.net.maxFrameLength=16777216\n");
        return List.copyOf(definitions);
    }

    private static void add(
            final List<ScaffoldComponentDefinition> definitions,
            final String id,
            final String expression,
            final ComponentId... providers) {
        add(definitions, id, expression, List.of(providers), List.of(), Map.of(), "");
    }

    private static void add(
            final List<ScaffoldComponentDefinition> definitions,
            final String id,
            final String expression,
            final List<ComponentId> providers,
            final List<MavenCoordinate> artifacts,
            final Map<String, String> defaults,
            final String example) {
        String normalizedExpression = expression.isEmpty() || expression.contains("(")
                ? expression : "group.zn.zero.runtime." + expression + ".module()";
        definitions.add(new ScaffoldComponentDefinition(id, providers, artifacts, normalizedExpression, defaults, example));
    }

    /** 单次选择的不可变结果。 */
    public record Selection(
            List<String> components,
            List<MavenCoordinate> artifacts,
            List<String> capabilities,
            List<String> providers,
            boolean external,
            Map<String, ScaffoldComponentDefinition> definitions) {

        public Selection {
            components = List.copyOf(components);
            artifacts = List.copyOf(artifacts);
            capabilities = List.copyOf(capabilities);
            providers = List.copyOf(providers);
            definitions = Map.copyOf(definitions);
        }

        ScaffoldComponentDefinition definition(final String id) {
            ScaffoldComponentDefinition definition = definitions.get(id);
            if (definition == null) {
                throw new IllegalArgumentException("component is not selected: " + id);
            }
            return definition;
        }
    }
}
