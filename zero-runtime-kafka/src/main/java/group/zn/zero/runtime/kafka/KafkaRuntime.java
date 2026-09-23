package group.zn.zero.runtime.kafka;

import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import group.zn.zero.security.SecurityMetadataVerifier;
import java.util.List;
import java.util.Map;

/** Kafka RPC integration; requires an explicitly installed logging capability. */
public final class KafkaRuntime {
    private KafkaRuntime() {
    }

    public static ProductionModuleFactory module() {
        return module(Map.of());
    }

    public static ProductionModuleFactory module(final Map<String, ?> properties) {
        return module(properties, SecurityMetadataVerifier.failClosed());
    }

    /**
     * 配置 Kafka 安全元数据验证器；在订阅请求前安装，不创建资源。
     * @param properties Kafka 客户端安全属性；不可为空。
     * @param verifier 应用提供的签名及 assertion 验证器；不可为空，必须线程安全。
     * @return 不可变模块工厂；可用于 runtime 装配。
     * @throws NullPointerException 必填参数为空时抛出。
     */
    public static ProductionModuleFactory module(
            final Map<String, ?> properties, final SecurityMetadataVerifier verifier) {
        java.util.Objects.requireNonNull(verifier, "verifier");
        Map<String, Object> checked = ProductionKafkaRpcProvider.validateClientProperties(properties);
        return context -> {
            ProductionKafkaRpcProvider.Resolution resolved = ProductionKafkaRpcProvider.resolve(
                    context.resolver(), context.startupBudget(), checked, verifier);
            return ProductionModule.single(resolved.provider(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(), resolved.diagnostic());
        };
    }
}
