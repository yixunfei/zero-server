package group.zn.zero.discovery.nacos;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import java.util.Locale;
import java.util.Objects;

/**
 * Nacos 服务发现模块工厂。
 *
 * <p>该工厂用于外部项目按统一 `ZeroConfig` 创建服务发现实现。默认模式为本地注册表，避免小型项目
 * 或单进程原型被强制绑定 Nacos。
 *
 * @author zn
 */
public final class NacosDiscoveryFactory {

    private NacosDiscoveryFactory() {
    }

    /**
     * 按统一配置创建服务发现实现。
     *
     * <p>`zero.discovery.mode` 支持 `local` 和 `nacos`。未配置时默认 `local`。
     *
     * @param config 统一配置；不可为空。
     * @return 服务发现实现；不可为空；未启动；线程安全性由具体实现声明。
     * @throws ZeroException 当模式未知或 Nacos 配置非法时抛出，必须绑定 ErrorCode。
     */
    public static ServiceDiscovery fromConfig(final ZeroConfig config) {
        Objects.requireNonNull(config, "config");
        String mode = config.get(NacosDiscoveryConfigKeys.DISCOVERY_MODE)
                .orElse(NacosDiscoveryConfigKeys.MODE_LOCAL)
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case NacosDiscoveryConfigKeys.MODE_LOCAL -> local();
            case NacosDiscoveryConfigKeys.MODE_NACOS -> nacos(config);
            default -> throw ZeroException.of(
                    DiscoveryErrorCode.DISCOVERY_CONFIGURATION_ERROR,
                    "unsupported discovery mode: " + mode,
                    null);
        };
    }

    /**
     * 创建本地内存服务发现实现。
     *
     * @return 本地服务发现实现；不可为空；未启动；线程安全。
     */
    public static InMemoryServiceDiscovery local() {
        return new InMemoryServiceDiscovery();
    }

    /**
     * 按统一配置创建 Nacos 服务发现实现。
     *
     * @param config 统一配置；不可为空。
     * @return Nacos 服务发现实现；不可为空；未启动；线程安全。
     * @throws ZeroException 当 Nacos 配置非法时抛出，必须绑定 ErrorCode。
     */
    public static NacosDiscoveryAdapter nacos(final ZeroConfig config) {
        return nacos(NacosDiscoverySettings.fromConfig(config));
    }

    /**
     * 按显式配置创建 Nacos 服务发现实现。
     *
     * @param settings Nacos 配置；不可为空。
     * @return Nacos 服务发现实现；不可为空；未启动；线程安全。
     */
    public static NacosDiscoveryAdapter nacos(final NacosDiscoverySettings settings) {
        return new NacosDiscoveryAdapter(Objects.requireNonNull(settings, "settings"));
    }
}
