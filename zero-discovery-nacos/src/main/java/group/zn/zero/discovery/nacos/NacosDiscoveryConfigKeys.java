package group.zn.zero.discovery.nacos;

/**
 * Nacos 服务发现标准配置键。
 *
 * <p>业务项目应优先使用 `zero.discovery.*` 配置键。`zero.nacos.*` 系统属性和 `ZERO_NACOS_*`
 * 环境变量只作为外部注入兼容入口保留。
 *
 * @author zn
 */
public final class NacosDiscoveryConfigKeys {

    /**
     * 服务发现模式配置键。
     */
    public static final String DISCOVERY_MODE = "zero.discovery.mode";

    /**
     * 本地服务发现模式。
     */
    public static final String MODE_LOCAL = "local";

    /**
     * Nacos 服务发现模式。
     */
    public static final String MODE_NACOS = "nacos";

    /**
     * Nacos 服务端地址配置键。
     */
    public static final String SERVER_ADDR = "zero.discovery.nacos.server-addr";

    /**
     * Nacos namespace 配置键。
     */
    public static final String NAMESPACE = "zero.discovery.nacos.namespace";

    /**
     * Nacos 用户名配置键。
     */
    public static final String USERNAME = "zero.discovery.nacos.username";

    /**
     * Nacos 密码配置键。
     */
    public static final String PASSWORD = "zero.discovery.nacos.password";

    /**
     * Nacos access key 配置键。
     */
    public static final String ACCESS_KEY = "zero.discovery.nacos.access-key";

    /**
     * Nacos secret key 配置键。
     */
    public static final String SECRET_KEY = "zero.discovery.nacos.secret-key";

    /**
     * 默认服务分组配置键。
     */
    public static final String DEFAULT_GROUP = "zero.discovery.nacos.default-group";

    /**
     * 默认集群配置键。
     */
    public static final String DEFAULT_CLUSTER = "zero.discovery.nacos.default-cluster";

    /**
     * Nacos 请求超时配置键，单位毫秒。
     */
    public static final String REQUEST_TIMEOUT_MILLIS = "zero.discovery.nacos.request-timeout-millis";

    /**
     * Nacos 启动加载本地命名缓存配置键。
     */
    public static final String NAMING_LOAD_CACHE_AT_START = "zero.discovery.nacos.naming-load-cache-at-start";

    /**
     * Nacos 健康状态更新模式配置键。
     */
    public static final String HEALTH_UPDATE_MODE = "zero.discovery.nacos.health-update-mode";

    /**
     * Nacos 服务端地址系统属性兼容键。
     */
    public static final String SYSTEM_SERVER_ADDR = "zero.nacos.serverAddr";

    /**
     * Nacos namespace 系统属性兼容键。
     */
    public static final String SYSTEM_NAMESPACE = "zero.nacos.namespace";

    /**
     * Nacos 用户名系统属性兼容键。
     */
    public static final String SYSTEM_USERNAME = "zero.nacos.username";

    /**
     * Nacos 密码系统属性兼容键。
     */
    public static final String SYSTEM_PASSWORD = "zero.nacos.password";

    /**
     * Nacos access key 系统属性兼容键。
     */
    public static final String SYSTEM_ACCESS_KEY = "zero.nacos.accessKey";

    /**
     * Nacos secret key 系统属性兼容键。
     */
    public static final String SYSTEM_SECRET_KEY = "zero.nacos.secretKey";

    /**
     * 默认服务分组系统属性兼容键。
     */
    public static final String SYSTEM_DEFAULT_GROUP = "zero.nacos.defaultGroup";

    /**
     * 默认集群系统属性兼容键。
     */
    public static final String SYSTEM_DEFAULT_CLUSTER = "zero.nacos.defaultCluster";

    /**
     * Nacos 请求超时系统属性兼容键。
     */
    public static final String SYSTEM_REQUEST_TIMEOUT_MILLIS = "zero.nacos.requestTimeoutMillis";

    /**
     * Nacos 启动加载本地命名缓存系统属性兼容键。
     */
    public static final String SYSTEM_NAMING_LOAD_CACHE_AT_START = "zero.nacos.namingLoadCacheAtStart";

    /**
     * Nacos 健康状态更新模式系统属性兼容键。
     */
    public static final String SYSTEM_HEALTH_UPDATE_MODE = "zero.nacos.healthUpdateMode";

    /**
     * Nacos 服务端地址环境变量。
     */
    public static final String ENV_SERVER_ADDR = "ZERO_NACOS_SERVER_ADDR";

    /**
     * Nacos namespace 环境变量。
     */
    public static final String ENV_NAMESPACE = "ZERO_NACOS_NAMESPACE";

    /**
     * Nacos 用户名环境变量。
     */
    public static final String ENV_USERNAME = "ZERO_NACOS_USERNAME";

    /**
     * Nacos 密码环境变量。
     */
    public static final String ENV_PASSWORD = "ZERO_NACOS_PASSWORD";

    /**
     * Nacos access key 环境变量。
     */
    public static final String ENV_ACCESS_KEY = "ZERO_NACOS_ACCESS_KEY";

    /**
     * Nacos secret key 环境变量。
     */
    public static final String ENV_SECRET_KEY = "ZERO_NACOS_SECRET_KEY";

    /**
     * 默认服务分组环境变量。
     */
    public static final String ENV_DEFAULT_GROUP = "ZERO_NACOS_DEFAULT_GROUP";

    /**
     * 默认集群环境变量。
     */
    public static final String ENV_DEFAULT_CLUSTER = "ZERO_NACOS_DEFAULT_CLUSTER";

    /**
     * Nacos 请求超时环境变量。
     */
    public static final String ENV_REQUEST_TIMEOUT_MILLIS = "ZERO_NACOS_REQUEST_TIMEOUT_MILLIS";

    /**
     * Nacos 启动加载本地命名缓存环境变量。
     */
    public static final String ENV_NAMING_LOAD_CACHE_AT_START = "ZERO_NACOS_NAMING_LOAD_CACHE_AT_START";

    /**
     * Nacos 健康状态更新模式环境变量。
     */
    public static final String ENV_HEALTH_UPDATE_MODE = "ZERO_NACOS_HEALTH_UPDATE_MODE";

    private NacosDiscoveryConfigKeys() {
    }
}
