package group.zn.zero.discovery.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Nacos 服务发现配置。
 *
 * @param serverAddr Nacos 服务端地址。
 * @param namespace 命名空间。
 * @param username 用户名。
 * @param password 密码。
 * @param accessKey 访问密钥。
 * @param secretKey 访问密钥秘钥。
 * @param defaultGroupName 默认服务分组。
 * @param defaultClusterName 默认集群名称。
 * @param requestTimeoutMillis 请求超时时间，单位毫秒。
 * @param namingLoadCacheAtStart 启动时是否加载本地命名缓存。
 * @param healthUpdateMode 健康状态更新模式。
 * @param extraProperties 额外 Nacos client 属性。
 * @author zn
 */
public record NacosDiscoverySettings(
        String serverAddr,
        String namespace,
        String username,
        String password,
        String accessKey,
        String secretKey,
        String defaultGroupName,
        String defaultClusterName,
        int requestTimeoutMillis,
        boolean namingLoadCacheAtStart,
        NacosHealthUpdateMode healthUpdateMode,
        Map<String, String> extraProperties) {

    /**
     * 默认 namespace。Nacos 3.x 默认 namespace 为 public，本配置显式写入以避免版本默认值差异。
     */
    public static final String DEFAULT_NAMESPACE = "public";

    /**
     * 创建 Nacos 服务发现配置。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     * @throws IllegalArgumentException 当地址、namespace、group、cluster 或请求超时非法时抛出。
     */
    public NacosDiscoverySettings {
        requireText(serverAddr, "serverAddr");
        requireText(namespace, "namespace");
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        accessKey = accessKey == null ? "" : accessKey;
        secretKey = secretKey == null ? "" : secretKey;
        requireText(defaultGroupName, "defaultGroupName");
        requireText(defaultClusterName, "defaultClusterName");
        if (requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        Objects.requireNonNull(healthUpdateMode, "healthUpdateMode");
        extraProperties = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(extraProperties, "extraProperties")));
    }

    /**
     * 创建默认配置。
     *
     * @param serverAddr Nacos 服务端地址；不可为空。
     * @return Nacos 配置；不可为空；线程安全。
     */
    public static NacosDiscoverySettings of(final String serverAddr) {
        return new NacosDiscoverySettings(
                serverAddr,
                DEFAULT_NAMESPACE,
                "",
                "",
                "",
                "",
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                3_000,
                false,
                NacosHealthUpdateMode.REREGISTER,
                Map.of());
    }

    /**
     * 从框架配置、系统属性和环境变量创建配置。
     *
     * <p>读取优先级：框架配置、系统属性、环境变量、默认值。该方法不会记录敏感字段。
     *
     * @param config 框架配置；不可为空。
     * @return Nacos 配置；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当必需配置缺失或非法时抛出。
     */
    public static NacosDiscoverySettings fromConfig(final ZeroConfig config) {
        return fromConfig(config, System::getProperty, System::getenv);
    }

    /**
     * 使用指定进程配置读取函数创建 Nacos 配置。
     *
     * <p>该入口让上层装配器能够在测试中隔离宿主进程，同时保持生产默认入口的来源优先级不变。
     * 读取函数只在当前调用线程同步执行，不修改业务数据。</p>
     *
     * @param config 框架配置；不可为空。
     * @param systemPropertyLookup JVM system property 读取函数；不可为空，可返回空。
     * @param environmentLookup 环境变量读取函数；不可为空，可返回空。
     * @return Nacos 配置；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当必需配置缺失或非法时抛出。
     * @throws NullPointerException 任一参数为空时抛出。
     */
    public static NacosDiscoverySettings fromConfig(
            final ZeroConfig config,
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        Objects.requireNonNull(config, "config");
        Function<String, String> propertyLookup = Objects.requireNonNull(
                systemPropertyLookup,
                "systemPropertyLookup");
        Function<String, String> envLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
        String serverAddr = read(config,
                NacosDiscoveryConfigKeys.SERVER_ADDR,
                NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR,
                NacosDiscoveryConfigKeys.ENV_SERVER_ADDR,
                propertyLookup,
                envLookup)
                .orElseThrow(() -> ZeroException.of(
                        DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR,
                        "missing Nacos server address: " + NacosDiscoveryConfigKeys.SERVER_ADDR,
                        null));
        String namespace = read(config,
                NacosDiscoveryConfigKeys.NAMESPACE,
                NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE,
                NacosDiscoveryConfigKeys.ENV_NAMESPACE,
                propertyLookup,
                envLookup)
                .orElse(DEFAULT_NAMESPACE);
        NacosCredentials credentials = readCredentials(config, propertyLookup, envLookup);
        String groupName = read(config,
                NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_GROUP,
                NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP,
                propertyLookup,
                envLookup)
                .orElse(ServiceDiscoveryConstants.DEFAULT_GROUP_NAME);
        String clusterName = read(config,
                NacosDiscoveryConfigKeys.DEFAULT_CLUSTER,
                NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_CLUSTER,
                NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER,
                propertyLookup,
                envLookup)
                .orElse(ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME);
        int timeoutMillis = read(config,
                NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS,
                NacosDiscoveryConfigKeys.SYSTEM_REQUEST_TIMEOUT_MILLIS,
                NacosDiscoveryConfigKeys.ENV_REQUEST_TIMEOUT_MILLIS,
                propertyLookup,
                envLookup)
                .map(value -> parsePositiveInt(value, "request-timeout-millis"))
                .orElse(3_000);
        boolean loadCache = readRaw(config,
                NacosDiscoveryConfigKeys.NAMING_LOAD_CACHE_AT_START,
                NacosDiscoveryConfigKeys.SYSTEM_NAMING_LOAD_CACHE_AT_START,
                NacosDiscoveryConfigKeys.ENV_NAMING_LOAD_CACHE_AT_START,
                propertyLookup,
                envLookup)
                .map(value -> parseStrictBoolean(value, "naming-load-cache-at-start"))
                .orElse(false);
        NacosHealthUpdateMode healthMode = read(config,
                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE,
                NacosDiscoveryConfigKeys.SYSTEM_HEALTH_UPDATE_MODE,
                NacosDiscoveryConfigKeys.ENV_HEALTH_UPDATE_MODE,
                propertyLookup,
                envLookup)
                .map(NacosDiscoverySettings::parseHealthUpdateMode)
                .orElse(NacosHealthUpdateMode.REREGISTER);
        return new NacosDiscoverySettings(
                serverAddr,
                namespace,
                credentials.username(),
                credentials.password(),
                credentials.accessKey(),
                credentials.secretKey(),
                groupName,
                clusterName,
                timeoutMillis,
                loadCache,
                healthMode,
                Map.of());
    }

    /**
     * 转换为 Nacos client 属性。
     *
     * @return 可变 Properties；不可为空；无序；非线程安全。
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.putAll(extraProperties);
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        properties.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT, Integer.toString(requestTimeoutMillis));
        properties.setProperty(PropertyKeyConst.NAMING_LOAD_CACHE_AT_START, Boolean.toString(namingLoadCacheAtStart));
        putIfPresent(properties, PropertyKeyConst.USERNAME, username);
        putIfPresent(properties, PropertyKeyConst.PASSWORD, password);
        putIfPresent(properties, PropertyKeyConst.ACCESS_KEY, accessKey);
        putIfPresent(properties, PropertyKeyConst.SECRET_KEY, secretKey);
        return properties;
    }

    /**
     * 返回不包含配置原值的安全文本。
     *
     * @return 安全配置摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "NacosDiscoverySettings{"
                + "serverAddrConfigured=" + hasText(serverAddr)
                + ", namespaceConfigured=" + hasText(namespace)
                + ", usernameConfigured=" + hasText(username)
                + ", passwordConfigured=" + hasText(password)
                + ", accessKeyConfigured=" + hasText(accessKey)
                + ", secretKeyConfigured=" + hasText(secretKey)
                + ", defaultGroupNameConfigured=" + hasText(defaultGroupName)
                + ", defaultClusterNameConfigured=" + hasText(defaultClusterName)
                + ", requestTimeoutConfigured=true"
                + ", namingLoadCacheAtStartConfigured=true"
                + ", healthUpdateModeConfigured=true"
                + ", extraPropertiesCount=" + extraProperties.size()
                + '}';
    }

    private static Optional<String> read(
            final ZeroConfig config,
            final String configKey,
            final String systemProperty,
            final String environmentVariable,
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        return readRaw(
                config,
                configKey,
                systemProperty,
                environmentVariable,
                systemPropertyLookup,
                environmentLookup)
                .filter(NacosDiscoverySettings::hasText);
    }

    private static NacosCredentials readCredentials(
            final ZeroConfig config,
            final Function<String, String> propertyLookup,
            final Function<String, String> environmentLookup) {
        String username = read(config,
                NacosDiscoveryConfigKeys.USERNAME,
                NacosDiscoveryConfigKeys.SYSTEM_USERNAME,
                NacosDiscoveryConfigKeys.ENV_USERNAME,
                propertyLookup,
                environmentLookup)
                .orElse("");
        String password = read(config,
                NacosDiscoveryConfigKeys.PASSWORD,
                NacosDiscoveryConfigKeys.SYSTEM_PASSWORD,
                NacosDiscoveryConfigKeys.ENV_PASSWORD,
                propertyLookup,
                environmentLookup)
                .orElse("");
        String accessKey = read(config,
                NacosDiscoveryConfigKeys.ACCESS_KEY,
                NacosDiscoveryConfigKeys.SYSTEM_ACCESS_KEY,
                NacosDiscoveryConfigKeys.ENV_ACCESS_KEY,
                propertyLookup,
                environmentLookup)
                .orElse("");
        String secretKey = read(config,
                NacosDiscoveryConfigKeys.SECRET_KEY,
                NacosDiscoveryConfigKeys.SYSTEM_SECRET_KEY,
                NacosDiscoveryConfigKeys.ENV_SECRET_KEY,
                propertyLookup,
                environmentLookup)
                .orElse("");
        return new NacosCredentials(username, password, accessKey, secretKey);
    }

    private static Optional<String> readRaw(
            final ZeroConfig config,
            final String configKey,
            final String systemProperty,
            final String environmentVariable,
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        return config.get(configKey)
                .or(() -> Optional.ofNullable(systemPropertyLookup.apply(systemProperty)))
                .or(() -> Optional.ofNullable(environmentLookup.apply(environmentVariable)));
    }

    private static boolean parseStrictBoolean(final String value, final String name) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw ZeroException.of(
                DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR,
                "invalid Nacos " + name + "; expected true or false",
                null);
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private static int parsePositiveInt(final String value, final String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw ZeroException.of(
                    DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR,
                    "invalid Nacos " + name + ": " + value,
                    ex);
        }
    }

    private static NacosHealthUpdateMode parseHealthUpdateMode(final String value) {
        try {
            return NacosHealthUpdateMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ZeroException.of(
                    DiscoveryErrorCode.NACOS_CONFIGURATION_ERROR,
                    "invalid Nacos health-update-mode: " + value,
                    ex);
        }
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void putIfPresent(final Properties properties, final String key, final String value) {
        if (!value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    /** Sensitive Nacos authentication values kept out of diagnostic text. */
    private record NacosCredentials(String username, String password, String accessKey, String secretKey) {
    }
}
