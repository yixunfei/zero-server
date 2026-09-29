package group.zn.zero.starter.production;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.log.LogSink;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.kafka.KafkaRuntime;
import group.zn.zero.runtime.mongo.MongoRuntime;
import group.zn.zero.runtime.nacos.NacosRuntime;
import group.zn.zero.runtime.net.NetworkRuntime;
import group.zn.zero.runtime.postgresql.PostgresqlRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import group.zn.zero.runtime.production.ZeroProductionAssemblyReport;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.runtime.redis.RedisRuntime;
import group.zn.zero.starter.LocalRuntime;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Objects;

/** Full convenience composition. Individual integrations are also independently installable. */
public final class ZeroProductionRuntimeBuilder {
    private final String profile;
    private final ZeroConfig config;
    private Supplier<? extends LogSink> terminalLogSink;
    private ZeroRuntimeExecutors executors;
    private CacheValueCodec<Object> redisCacheValueCodec;
    private ProductionModuleFactory kafkaModule = KafkaRuntime.module();
    private ProductionNetworkPolicy networkPolicy;
    private SecurityChain securityChain;
    private NetworkRateLimiter networkRateLimiter;
    /** 按需接入的网络观测下游；不随日志或指标模块自动启用。 */
    private ConnectionLifecycleObserver networkObserver;
    private Function<String, String> systemPropertyLookup = System::getProperty;
    private Function<String, String> environmentLookup = System::getenv;
    private boolean consumed;

    ZeroProductionRuntimeBuilder(
            final String profile, final ZeroConfig config,
            final Supplier<? extends LogSink> terminalLogSink, final ZeroRuntimeExecutors executors) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.config = Objects.requireNonNull(config, "config");
        this.terminalLogSink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public ZeroProductionRuntimeBuilder configSourceLookups(
            final Function<String, String> properties, final Function<String, String> environment) {
        mutable();
        systemPropertyLookup = Objects.requireNonNull(properties, "properties");
        environmentLookup = Objects.requireNonNull(environment, "environment");
        return this;
    }

    public ZeroProductionRuntimeBuilder terminalLogSink(final LogSink sink) {
        mutable();
        LogSink checked = Objects.requireNonNull(sink, "sink");
        terminalLogSink = () -> checked;
        return this;
    }

    public ZeroProductionRuntimeBuilder executors(final ZeroRuntimeExecutors value) {
        mutable();
        executors = Objects.requireNonNull(value, "executors");
        return this;
    }

    public ZeroProductionRuntimeBuilder redisCacheValueCodec(final CacheValueCodec<Object> codec) {
        mutable();
        redisCacheValueCodec = Objects.requireNonNull(codec, "codec");
        return this;
    }

    public ZeroProductionRuntimeBuilder kafkaClientProperties(final Map<String, ?> properties) {
        mutable();
        kafkaModule = KafkaRuntime.module(properties);
        return this;
    }

    public ZeroProductionRuntimeBuilder networkPolicy(final ProductionNetworkPolicy policy) {
        mutable();
        networkPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    public ZeroProductionRuntimeBuilder networkRateLimiter(final NetworkRateLimiter limiter) {
        mutable();
        networkRateLimiter = Objects.requireNonNull(limiter, "limiter");
        return this;
    }
    public ZeroProductionRuntimeBuilder securityChain(final SecurityChain value) {
        mutable();
        securityChain = Objects.requireNonNull(value, "securityChain");
        return this;
    }

    /**
     * 显式接入网络观测下游；未调用时使用 no-op，即使已安装日志和监控模块。
     * @param observer 线程安全的观测器；不可为空，下游资源由调用方管理。
     * @return 当前可变 builder；非线程安全。
     * @throws NullPointerException 观测器为空。
     * @throws IllegalStateException builder 已被消费。
     */
    public ZeroProductionRuntimeBuilder networkObserver(final ConnectionLifecycleObserver observer) {
        mutable();
        networkObserver = Objects.requireNonNull(observer, "observer");
        return this;
    }


    public ZeroProductionAssemblyReport diagnose() {
        mutable();
        return assembly().diagnose();
    }

    public ZeroProductionRuntime build() {
        mutable();
        consumed = true;
        return assembly().build();
    }

    private ProductionAssembly assembly() {
        return ProductionAssembly.builder(profile, config, executors)
                .base(LocalRuntime.module(config, terminalLogSink, executors))
                .configSourceLookups(systemPropertyLookup, environmentLookup)
                .install(kafkaModule)
                .install(MongoRuntime.module())
                .install(PostgresqlRuntime.module())
                .install(RedisRuntime.module(redisCacheValueCodec))
                .install(NacosRuntime.module())
                .install(NetworkRuntime.module(networkPolicy, networkRateLimiter, securityChain, networkObserver));
    }

    private void mutable() {
        if (consumed) {
            throw new IllegalStateException("production runtime builder has already been consumed");
        }
    }
}
