package group.zn.zero.runtime.capability;

import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.api.ComponentId;
import java.util.List;

/**
 * zeroServer 内置中立能力词汇；只声明逻辑事实，不引用任何实现类。
 *
 * @author zn
 */
public final class StandardRuntimeCapabilityModel {

    public static final String PROFILE_MINIMAL = "minimal";
    public static final String PROFILE_LOCAL = "local";
    public static final String PROFILE_STANDALONE = "standalone";
    public static final String PROFILE_EXTERNAL_TEST = "external-test";
    public static final String PROFILE_PRODUCTION = "production";

    public static final String CONFIG = "zero.config";
    public static final String EXECUTORS = "zero.executors";
    public static final String TERMINAL_LOG_SINK = "zero.log.sink";
    public static final String LOG_APPENDER = "zero.log.appender";
    public static final String DEAD_LETTER_SINK = "zero.event.dead-letter";
    public static final String EVENT_BUS = "zero.event.bus";
    public static final String ACTOR_SCHEDULER = "zero.actor.scheduler";
    public static final String PROTOCOL_REGISTRY = "zero.protocol.registry";
    public static final String RPC_TRANSPORT = "zero.rpc.transport";
    public static final String RPC_HANDLER_REGISTRY = "zero.rpc.handler-registry";
    public static final String PERSISTENCE_MANAGER = "zero.data.persistence";
    public static final String CACHE_SERVICE = "zero.cache.service";
    public static final String MONITOR_RUNTIME = "zero.monitor.runtime";
    public static final String DATA_SERVICES = "zero.data.services";
    public static final String REDIS_RESOURCE = "zero.data.redis-resource";
    public static final String SERVICE_DISCOVERY = "zero.discovery.service";
    public static final String RPC_SERVICE_RESOLVER = "zero.rpc.service-resolver";
    public static final String NETWORK_LIFECYCLE = "zero.net.connection-lifecycle";
    public static final String INFRASTRUCTURE_LIFECYCLES = "zero.lifecycle.infrastructure";
    public static final String APPLICATION_LIFECYCLES = "zero.lifecycle.application";

    public static final ComponentId LOCAL_CONFIG = ComponentId.of("zero.local.config");
    public static final ComponentId LOCAL_EXECUTORS = ComponentId.of("zero.local.executors");
    public static final ComponentId LOCAL_LOG_SINK = ComponentId.of("zero.local.log-sink");
    public static final ComponentId LOCAL_LOG_APPENDER = ComponentId.of("zero.local.log-appender");
    public static final ComponentId LOCAL_DEAD_LETTER = ComponentId.of("zero.local.dead-letter");
    public static final ComponentId LOCAL_EVENT_BUS = ComponentId.of("zero.local.event-bus");
    public static final ComponentId LOCAL_ACTOR = ComponentId.of("zero.local.actor");
    public static final ComponentId LOCAL_PROTOCOL = ComponentId.of("zero.local.protocol");
    public static final ComponentId LOCAL_RPC = ComponentId.of("zero.local.rpc");
    public static final ComponentId LOCAL_PERSISTENCE = ComponentId.of("zero.local.persistence");
    public static final ComponentId LOCAL_CACHE = ComponentId.of("zero.local.cache");
    public static final ComponentId LOCAL_MONITOR = ComponentId.of("zero.local.monitor");
    public static final ComponentId PRODUCTION_KAFKA_RPC = ComponentId.of("zero.production.kafka-rpc");
    public static final ComponentId PRODUCTION_MONGO_DATA = ComponentId.of("zero.production.mongo-data");
    public static final ComponentId PRODUCTION_NACOS_DISCOVERY =
            ComponentId.of("zero.production.nacos-discovery");
    public static final ComponentId PRODUCTION_NACOS_RPC_RESOLVER =
            ComponentId.of("zero.production.nacos-rpc-resolver");
    public static final ComponentId PRODUCTION_POSTGRESQL_DATA =
            ComponentId.of("zero.production.postgresql-data");
    public static final ComponentId PRODUCTION_NETWORK_LIFECYCLE =
            ComponentId.of("zero.production.network-lifecycle");
    public static final ComponentId PRODUCTION_REDIS_RESOURCE = ComponentId.of("zero.production.redis-resource");
    public static final ComponentId PRODUCTION_REDIS_DATA = ComponentId.of("zero.production.redis-data");
    public static final ComponentId PRODUCTION_REDIS_CACHE = ComponentId.of("zero.production.redis-cache");

    private static final List<String> STANDARD_PROFILES = List.of(
            PROFILE_EXTERNAL_TEST,
            PROFILE_LOCAL,
            PROFILE_MINIMAL,
            PROFILE_PRODUCTION,
            PROFILE_STANDALONE);
    private static final List<String> EXTERNAL_PROFILES = List.of(
            PROFILE_EXTERNAL_TEST,
            PROFILE_PRODUCTION,
            PROFILE_STANDALONE);
    private static final RuntimeCapabilityModel INSTANCE = createModel();

    private StandardRuntimeCapabilityModel() {
    }

    /**
     * 返回进程内共享的不可变标准模型。
     *
     * @return 标准能力模型；不可为空。
     */
    public static RuntimeCapabilityModel instance() {
        return INSTANCE;
    }

    private static RuntimeCapabilityModel createModel() {
        RuntimeCapabilityModel.Builder model = RuntimeCapabilityModel.builder("zero.standard");
        addCapabilities(model);
        addLocalProviders(model);
        addProductionProviders(model);
        return model.build();
    }

    private static void addCapabilities(final RuntimeCapabilityModel.Builder model) {
        add(model, CONFIG, List.of(), "zero-core");
        add(model, EXECUTORS, List.of(), "zero-server-starter");
        add(model, TERMINAL_LOG_SINK, List.of(), "zero-log");
        add(model, LOG_APPENDER, List.of(CONFIG, TERMINAL_LOG_SINK), "zero-log");
        add(model, DEAD_LETTER_SINK, List.of(), "zero-event");
        add(model, EVENT_BUS, List.of(DEAD_LETTER_SINK), "zero-event");
        add(model, ACTOR_SCHEDULER, List.of(EXECUTORS), "zero-actor");
        add(model, PROTOCOL_REGISTRY, List.of(), "zero-protocol");
        add(model, RPC_TRANSPORT, List.of(), "zero-rpc");
        add(model, RPC_HANDLER_REGISTRY, List.of(), "zero-rpc");
        add(model, PERSISTENCE_MANAGER, List.of(), "zero-data");
        add(model, CACHE_SERVICE, List.of(), "zero-cache");
        add(model, MONITOR_RUNTIME, List.of(), "zero-monitor");
        addMultiple(model, DATA_SERVICES, List.of(), "zero-data", EXTERNAL_PROFILES);
        addExternal(model, REDIS_RESOURCE, List.of(), "zero-data-redis");
        addExternal(model, SERVICE_DISCOVERY, List.of(), "zero-discovery-nacos");
        addExternal(model, RPC_SERVICE_RESOLVER, List.of(), "zero-rpc");
        addExternal(model, NETWORK_LIFECYCLE, List.of(
                CONFIG,
                EXECUTORS,
                LOG_APPENDER,
                MONITOR_RUNTIME), "zero-net");
        addMultiple(model, INFRASTRUCTURE_LIFECYCLES, List.of(), "zero-runtime");
        addMultiple(model, APPLICATION_LIFECYCLES, List.of(PERSISTENCE_MANAGER), "zero-runtime");
    }

    private static void addLocalProviders(final RuntimeCapabilityModel.Builder model) {
        provider(model, LOCAL_CONFIG, CONFIG);
        provider(model, LOCAL_EXECUTORS, EXECUTORS);
        provider(model, LOCAL_LOG_SINK, TERMINAL_LOG_SINK);
        provider(model, LOCAL_LOG_APPENDER, LOG_APPENDER);
        provider(model, LOCAL_DEAD_LETTER, DEAD_LETTER_SINK);
        provider(model, LOCAL_EVENT_BUS, EVENT_BUS);
        provider(model, LOCAL_ACTOR, ACTOR_SCHEDULER);
        provider(model, LOCAL_PROTOCOL, PROTOCOL_REGISTRY);
        model.provider(new RuntimeProviderCapability(
                LOCAL_RPC,
                List.of(RPC_HANDLER_REGISTRY, RPC_TRANSPORT),
                List.of(),
                STANDARD_PROFILES,
                List.of()));
        provider(model, LOCAL_PERSISTENCE, PERSISTENCE_MANAGER);
        provider(model, LOCAL_CACHE, CACHE_SERVICE);
        provider(model, LOCAL_MONITOR, MONITOR_RUNTIME);
    }

    private static void addProductionProviders(final RuntimeCapabilityModel.Builder model) {
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_KAFKA_RPC,
                List.of(RPC_HANDLER_REGISTRY, RPC_TRANSPORT),
                List.of(LOG_APPENDER),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-rpc-kafka"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_MONGO_DATA,
                List.of(DATA_SERVICES),
                List.of(),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-data-mongo"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_NACOS_DISCOVERY,
                List.of(SERVICE_DISCOVERY),
                List.of(),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-discovery-nacos"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_NACOS_RPC_RESOLVER,
                List.of(RPC_SERVICE_RESOLVER),
                List.of(SERVICE_DISCOVERY),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-discovery-nacos"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_NETWORK_LIFECYCLE,
                List.of(NETWORK_LIFECYCLE),
                List.of(),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-net"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_POSTGRESQL_DATA,
                List.of(DATA_SERVICES),
                List.of(),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-data-postgresql"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_REDIS_RESOURCE,
                List.of(REDIS_RESOURCE),
                List.of(),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-data-redis"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_REDIS_DATA,
                List.of(DATA_SERVICES),
                List.of(REDIS_RESOURCE),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-data-redis"))));
        model.provider(new RuntimeProviderCapability(
                PRODUCTION_REDIS_CACHE,
                List.of(CACHE_SERVICE),
                List.of(REDIS_RESOURCE),
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero("zero-data-redis"))));
    }

    private static void add(
            final RuntimeCapabilityModel.Builder model,
            final String id,
            final List<String> requires,
            final String artifactId) {
        model.capability(new RuntimeCapability(
                id,
                BindingCardinality.SINGLE,
                requires,
                STANDARD_PROFILES,
                List.of(MavenCoordinate.zero(artifactId))));
    }

    private static void addExternal(
            final RuntimeCapabilityModel.Builder model,
            final String id,
            final List<String> requires,
            final String artifactId) {
        model.capability(new RuntimeCapability(
                id,
                BindingCardinality.SINGLE,
                requires,
                EXTERNAL_PROFILES,
                List.of(MavenCoordinate.zero(artifactId))));
    }

    private static void addMultiple(
            final RuntimeCapabilityModel.Builder model,
            final String id,
            final List<String> requires,
            final String artifactId) {
        addMultiple(model, id, requires, artifactId, STANDARD_PROFILES);
    }

    private static void addMultiple(
            final RuntimeCapabilityModel.Builder model,
            final String id,
            final List<String> requires,
            final String artifactId,
            final List<String> profiles) {
        model.capability(new RuntimeCapability(
                id,
                BindingCardinality.MULTIPLE,
                requires,
                profiles,
                List.of(MavenCoordinate.zero(artifactId))));
    }

    private static void provider(
            final RuntimeCapabilityModel.Builder model,
            final ComponentId providerId,
            final String capabilityId) {
        model.provider(new RuntimeProviderCapability(
                providerId, List.of(capabilityId), List.of(), STANDARD_PROFILES, List.of()));
    }
}
