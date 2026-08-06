package group.zn.zero.discovery.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.ZeroException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Nacos 服务发现适配器测试。
 *
 * @author zn
 */
class NacosDiscoveryAdapterTest {

    /**
     * 验证启动健康失败后的关闭异常作为 suppressed 保留，不能覆盖主失败。
     */
    @Test
    void startShouldPreserveReadinessFailureWhenCleanupFails() {
        NamingService namingService = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "DOWN";
            case "shutDown" -> throw nacosFailure("shutdown-secret");
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(1), properties -> namingService);

        ZeroException exception = assertThrows(ZeroException.class, adapter::start);

        assertEquals(DiscoveryErrorCode.REGISTRY_UNAVAILABLE, exception.errorCode());
        assertEquals(1, exception.getSuppressed().length);
        assertTrue(exception.getSuppressed()[0] instanceof ZeroException);
        assertEquals(
                DiscoveryErrorCode.NACOS_CLIENT_ERROR,
                ((ZeroException) exception.getSuppressed()[0]).errorCode());
    }

    /**
     * 验证 readiness 与 shutdown 同时抛出 {@link Error} 时，readiness 仍是主失败且清理失败可见。
     */
    @Test
    void startShouldPreserveRawErrorAndAttachShutdownError() {
        AssertionError readinessFailure = new AssertionError("readiness-error");
        AssertionError shutdownFailure = new AssertionError("shutdown-error");
        NamingService namingService = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> throw readinessFailure;
            case "shutDown" -> throw shutdownFailure;
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(10), properties -> namingService);

        ZeroException exception = assertThrows(ZeroException.class, adapter::start);

        assertSame(readinessFailure, exception.getCause());
        assertEquals(1, exception.getSuppressed().length);
        assertSame(shutdownFailure, exception.getSuppressed()[0].getCause());
    }

    /**
     * 验证停止流程会尽力执行全部远端清理，并把后续失败挂到首个失败上。
     */
    @Test
    void stopShouldAggregateAllCleanupFailuresAndRemainIdempotent() {
        List<String> cleanupCalls = new ArrayList<>();
        NamingService namingService = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "getAllInstances", "selectInstances" -> List.of();
            case "unsubscribe", "shutDown" -> {
                cleanupCalls.add(method.getName());
                throw new AssertionError(method.getName());
            }
            case "deregisterInstance" -> {
                cleanupCalls.add(method.getName());
                throw nacosFailure(method.getName());
            }
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(10), properties -> namingService);
        adapter.start();
        adapter.register(instance());
        adapter.subscribe(ServiceQuery.of("logic-service"), event -> { });

        ZeroException exception = assertThrows(ZeroException.class, adapter::stop);
        int callCountAfterFailure = cleanupCalls.size();
        adapter.stop();

        assertEquals(List.of("unsubscribe", "deregisterInstance", "shutDown"), cleanupCalls);
        assertEquals(2, exception.getSuppressed().length);
        assertEquals(callCountAfterFailure, cleanupCalls.size());
    }

    /**
     * 验证远端取消订阅失败后句柄仍可重试，成功后重复关闭不再调用远端。
     */
    @Test
    void subscriptionShouldRemainRetryableAfterRemoteFailure() {
        AtomicInteger unsubscribeCalls = new AtomicInteger();
        NamingService namingService = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "subscribe", "shutDown" -> null;
            case "unsubscribe" -> {
                if (unsubscribeCalls.incrementAndGet() == 1) {
                    throw nacosFailure("first-unsubscribe");
                }
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(10), properties -> namingService);
        adapter.start();
        ServiceSubscription subscription = adapter.subscribe(ServiceQuery.of("logic-service"), event -> { });

        assertThrows(ZeroException.class, subscription::close);
        subscription.close();
        subscription.close();
        adapter.stop();

        assertEquals(2, unsubscribeCalls.get());
    }

    /**
     * 验证 Nacos 配置文本不会泄露地址、身份、凭据、隔离标识或扩展属性值。
     */
    @Test
    void settingsToStringShouldNotExposeConfigurationValues() {
        List<String> secrets = List.of(
                "nacos-server-secret",
                "nacos-namespace-secret",
                "nacos-user-secret",
                "nacos-password-secret",
                "nacos-access-secret",
                "nacos-secret-key",
                "nacos-group-secret",
                "nacos-cluster-secret",
                "nacos-extra-secret");
        NacosDiscoverySettings settings = new NacosDiscoverySettings(
                secrets.get(0),
                secrets.get(1),
                secrets.get(2),
                secrets.get(3),
                secrets.get(4),
                secrets.get(5),
                secrets.get(6),
                secrets.get(7),
                3_001,
                true,
                NacosHealthUpdateMode.REREGISTER,
                Map.of("custom.property", secrets.get(8)));

        String text = settings.toString();

        for (String secret : secrets) {
            assertFalse(text.contains(secret), text);
        }
        assertFalse(text.contains("requestTimeoutMillis=3001"), text);
        assertFalse(text.contains("namingLoadCacheAtStart=true"), text);
        assertFalse(text.contains("healthUpdateMode=REREGISTER"), text);
        assertTrue(text.contains("serverAddrConfigured=true"), text);
        assertTrue(text.contains("requestTimeoutConfigured=true"), text);
        assertTrue(text.contains("extraPropertiesCount=1"), text);
    }

    /**
     * 验证 Adapter 创建 Nacos SDK client 时会把配置中的原生请求 timeout 传入驱动属性。
     */
    @Test
    void startShouldPassRequestTimeoutToNacosClientProperties() {
        AtomicReference<Properties> captured = new AtomicReference<>();
        NamingService namingService = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "shutDown" -> null;
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(1_237),
                properties -> {
                    Properties snapshot = new Properties();
                    snapshot.putAll(properties);
                    captured.set(snapshot);
                    return namingService;
                });

        adapter.start();
        adapter.stop();

        assertEquals(
                "1237",
                captured.get().getProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT));
    }

    /**
     * 验证启动缓存布尔开关只接受精确小写 true 或 false。
     */
    @Test
    void settingsShouldParseLoadCacheBooleanStrictly() {
        NacosDiscoverySettings enabled = settingsWithLoadCache("true");
        NacosDiscoverySettings disabled = settingsWithLoadCache("false");

        assertTrue(enabled.namingLoadCacheAtStart());
        assertFalse(disabled.namingLoadCacheAtStart());
        for (String invalid : List.of("TRUE", "False", "yes", "1", " true ", "", " ")) {
            ZeroException exception = assertThrows(
                    ZeroException.class,
                    () -> settingsWithLoadCache(invalid),
                    invalid);
            assertSame(DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR, exception.errorCode());
        }
    }

    /**
     * 验证本地注册表策略可以在同一套 API 下注册、查询、更新健康和注销。
     */
    @Test
    void localRegistryShouldRegisterLookupUpdateAndUnregister() {
        InMemoryServiceDiscovery registry = new InMemoryServiceDiscovery();
        List<ServiceEventType> events = new ArrayList<>();
        registry.addListener(event -> events.add(event.type()));
        ServiceInstance instance = new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of("zone", "local"));

        registry.start();
        registry.register(instance);
        registry.updateHealth("logic-service", "logic-1", false);
        List<ServiceInstance> instances = registry.lookup("logic-service");
        registry.unregister("logic-service", "logic-1");

        assertEquals(1, instances.size());
        assertFalse(instances.getFirst().healthy());
        assertEquals(List.of(
                ServiceEventType.REGISTERED,
                ServiceEventType.HEALTH_CHANGED,
                ServiceEventType.UNREGISTERED), events);
    }

    /**
     * 验证本地订阅只暴露最终实例快照。
     */
    @Test
    void localRegistryShouldNotifySnapshotSubscription() {
        InMemoryServiceDiscovery registry = new InMemoryServiceDiscovery();
        List<ServiceEvent> events = new ArrayList<>();
        ServiceInstance instance = new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of());

        registry.start();
        ServiceSubscription subscription = registry.subscribe(ServiceQuery.of("logic-service"), events::add);
        registry.register(instance);
        subscription.close();
        registry.unregister("logic-service", "logic-1");

        assertEquals(1, events.size());
        assertEquals(ServiceEventType.SNAPSHOT_CHANGED, events.getFirst().type());
        assertEquals(List.of(instance), events.getFirst().instances());
    }

    /**
     * 验证监听器异常会被记录，并且不会破坏注册表状态。
     */
    @Test
    void localRegistryShouldRecordListenerFailure() {
        InMemoryServiceDiscovery registry = new InMemoryServiceDiscovery();
        registry.addListener(event -> {
            throw new IllegalStateException("listener failed");
        });

        registry.start();
        registry.register(new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of()));

        assertEquals(1, registry.lookup("logic-service").size());
        assertEquals(1, registry.listenerFailures().size());
        assertEquals(DiscoveryErrorCode.LISTENER_FAILED, registry.listenerFailures().getFirst().errorCode());
    }

    /**
     * 验证 Nacos Adapter 未启动时会拒绝访问注册表。
     */
    @Test
    void adapterShouldFailWhenStopped() {
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter();

        ZeroException exception = assertThrows(ZeroException.class, () -> adapter.register(new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of())));

        assertEquals(DiscoveryErrorCode.REGISTRY_UNAVAILABLE, exception.errorCode());
        assertEquals("nacos", adapter.adapterName());
    }

    /**
     * 验证 Nacos 配置会显式保留 namespace 和健康更新策略。
     */
    @Test
    void settingsShouldReadExplicitNamespaceAndHealthMode() {
        NacosDiscoverySettings settings = NacosDiscoverySettings.fromConfig(new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.NAMESPACE, "public",
                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE, "local_only"
        )));

        assertEquals("127.0.0.1:8848", settings.serverAddr());
        assertEquals("public", settings.namespace());
        assertEquals(NacosHealthUpdateMode.LOCAL_ONLY, settings.healthUpdateMode());
        assertTrue(settings.toProperties().containsKey("namespace"));
    }

    /**
     * 验证可注入配置来源只读取调用方提供的 system/environment lookup，且仍保持配置文件优先级。
     */
    @Test
    void settingsShouldUseInjectedProcessSourceLookups() {
        NacosDiscoverySettings settings = NacosDiscoverySettings.fromConfig(
                new MapZeroConfig(Map.of(
                        NacosDiscoveryConfigKeys.SERVER_ADDR, "config-server:8848",
                        NacosDiscoveryConfigKeys.NAMESPACE, "config-namespace")),
                key -> switch (key) {
                    case NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR -> "property-server:8848";
                    case NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE -> "property-namespace";
                    case NacosDiscoveryConfigKeys.SYSTEM_REQUEST_TIMEOUT_MILLIS -> "1237";
                    default -> null;
                },
                key -> switch (key) {
                    case NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP -> "ENV_GROUP";
                    case NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER -> "ENV_CLUSTER";
                    default -> null;
                });

        assertEquals("config-server:8848", settings.serverAddr());
        assertEquals("config-namespace", settings.namespace());
        assertEquals(1_237, settings.requestTimeoutMillis());
        assertEquals("ENV_GROUP", settings.defaultGroupName());
        assertEquals("ENV_CLUSTER", settings.defaultClusterName());
    }

    /**
     * 验证服务发现模型拒绝空白关键字段。
     */
    @Test
    void modelShouldRejectBlankIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceInstance(
                " ",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ServiceQuery(
                "logic-service",
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                List.of(" "),
                false,
                false));
    }

    /**
     * 验证非法健康策略会转换为服务发现配置错误。
     */
    @Test
    void settingsShouldRejectInvalidHealthModeWithDiscoveryErrorCode() {
        ZeroException exception = assertThrows(ZeroException.class, () -> NacosDiscoverySettings.fromConfig(new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE, "bad-mode"
        ))));

        assertEquals(DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR, exception.errorCode());
    }

    /**
     * 验证工厂默认创建本地服务发现实现。
     */
    @Test
    void factoryShouldCreateLocalDiscoveryByDefault() {
        ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(new MapZeroConfig(Map.of()));

        assertTrue(discovery instanceof InMemoryServiceDiscovery);
    }

    /**
     * 验证工厂可以按统一配置创建 Nacos Adapter。
     */
    @Test
    void factoryShouldCreateNacosDiscoveryFromConfig() {
        ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS,
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.NAMESPACE, "public"
        )));

        assertTrue(discovery instanceof NacosDiscoveryAdapter);
        assertEquals("public", ((NacosDiscoveryAdapter) discovery).settings().namespace());
    }

    /**
     * 验证未知服务发现模式会绑定服务发现配置错误码。
     */
    @Test
    void factoryShouldRejectUnknownDiscoveryMode() {
        ZeroException exception = assertThrows(ZeroException.class, () -> NacosDiscoveryFactory.fromConfig(new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.DISCOVERY_MODE, "unknown"
        ))));

        assertEquals(DiscoveryErrorCode.DISCOVERY_CONFIGURATION_ERROR, exception.errorCode());
    }

    private static NacosDiscoverySettings settingsWithLoadCache(final String value) {
        return NacosDiscoverySettings.fromConfig(new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.NAMING_LOAD_CACHE_AT_START, value)));
    }

    private static NacosDiscoverySettings settings(final int timeoutMillis) {
        return new NacosDiscoverySettings(
                "127.0.0.1:8848",
                NacosDiscoverySettings.DEFAULT_NAMESPACE,
                "",
                "",
                "",
                "",
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                timeoutMillis,
                false,
                NacosHealthUpdateMode.REREGISTER,
                Map.of());
    }

    private static ServiceInstance instance() {
        return new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of());
    }

    private static NamingService namingService(final InvocationHandler handler) {
        return (NamingService) Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[] {NamingService.class},
                handler);
    }

    private static NacosException nacosFailure(final String message) {
        return new NacosException(NacosException.SERVER_ERROR, message);
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
