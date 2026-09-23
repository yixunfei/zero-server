package group.zn.zero.rpc.discovery;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.discovery.DiscoveryErrorCode;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import group.zn.zero.rpc.discovery.RpcServiceInstance;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Nacos 服务实例与 RPC 服务发现 metadata 映射工具。
 *
 * <p>该工具位于 Nacos adapter 模块中，负责把 Nacos `ServiceInstance` 转换为 `zero-rpc`
 * 的中立服务发现模型；`zero-rpc` 本身不依赖 Nacos SDK 或本模块。
 *
 * @author zn
 */
public final class NacosRpcMetadataMapper {

    private NacosRpcMetadataMapper() {
    }

    /**
     * 创建 RPC provider metadata。
     *
     * @param serviceName RPC 服务名；不可为空。
     * @param serviceVersion RPC 服务版本；必须为正数。
     * @param transport 传输名称；不可为空。
     * @param requestTopic request topic；可为空。
     * @param consumerGroup consumer group；可为空。
     * @param instanceId 实例标识；可为空。
     * @param protocolVersion 协议版本；可为空。
     * @param zone 区服或可用区；可为空。
     * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
     * @return metadata；不可为空；不可变、无序、可能为空、线程安全。
     */
    public static Map<String, String> rpcProviderMetadata(
            final String serviceName,
            final int serviceVersion,
            final String transport,
            final String requestTopic,
            final String consumerGroup,
            final String instanceId,
            final String protocolVersion,
            final String zone,
            final Map<String, String> attributes) {
        return RpcDiscoveryMetadata.providerMetadata(
                serviceName,
                serviceVersion,
                transport,
                requestTopic,
                consumerGroup,
                instanceId,
                protocolVersion,
                zone,
                attributes);
    }

    /**
     * 将 Nacos 服务实例转换为 RPC 服务实例。
     *
     * @param instance Nacos 服务实例模型；不可为空。
     * @return RPC 服务实例；不可为空；线程安全。
     * @throws ZeroException 当 RPC metadata 缺失或非法时抛出。
     */
    public static RpcServiceInstance toRpcServiceInstance(final ServiceInstance instance) {
        ServiceInstance current = Objects.requireNonNull(instance, "instance");
        Map<String, String> metadata = current.metadata();
        String serviceName = firstPresent(metadata, RpcDiscoveryMetadata.SERVICE_NAME, current.serviceName());
        int serviceVersion = parseVersion(metadata, current.serviceName());
        String transport = firstPresent(
                metadata,
                RpcDiscoveryMetadata.TRANSPORT,
                firstPresent(metadata, RpcDiscoveryMetadata.PROTOCOL, ""));
        if (transport.isBlank()) {
            throw invalidMetadata("missing RPC transport metadata: " + current.serviceName());
        }
        return new RpcServiceInstance(
                serviceName,
                serviceVersion,
                firstPresent(metadata, RpcDiscoveryMetadata.INSTANCE_ID, current.instanceId()),
                transport,
                firstPresent(
                        metadata,
                        RpcDiscoveryMetadata.REQUEST_TOPIC,
                        firstPresent(metadata, RpcDiscoveryMetadata.TOPIC, "")),
                firstPresent(
                        metadata,
                        RpcDiscoveryMetadata.CONSUMER_GROUP,
                        firstPresent(metadata, RpcDiscoveryMetadata.GROUP, "")),
                current.host(),
                current.port(),
                current.groupName(),
                current.clusterName(),
                firstPresent(metadata, RpcDiscoveryMetadata.ZONE, RpcDiscoveryMetadata.DEFAULT_ZONE),
                current.healthy(),
                current.enabled(),
                current.weight(),
                metadata);
    }

    /**
     * 将 RPC 服务实例转换为 Nacos 服务实例。
     *
     * @param instance RPC 服务实例；不可为空。
     * @return Nacos 服务实例模型；不可为空；线程安全。
     */
    public static ServiceInstance toServiceInstance(final RpcServiceInstance instance) {
        RpcServiceInstance current = Objects.requireNonNull(instance, "instance");
        Map<String, String> metadata = new LinkedHashMap<>(current.attributes());
        metadata.putAll(rpcProviderMetadata(
                current.serviceName(),
                current.serviceVersion(),
                current.transport(),
                current.requestTopic(),
                current.consumerGroup(),
                current.instanceId(),
                current.attributes().get(RpcDiscoveryMetadata.PROTOCOL_VERSION),
                current.zone(),
                current.attributes()));
        return new ServiceInstance(
                current.serviceName(),
                current.instanceId(),
                current.host(),
                current.port(),
                current.groupName(),
                current.clusterName(),
                current.healthy(),
                current.enabled(),
                true,
                current.weight(),
                1.0D,
                metadata);
    }

    private static int parseVersion(final Map<String, String> metadata, final String serviceName) {
        String version = firstPresent(
                metadata,
                RpcDiscoveryMetadata.SERVICE_VERSION,
                firstPresent(metadata, RpcDiscoveryMetadata.VERSION, ""));
        if (version.isBlank()) {
            throw invalidMetadata("missing RPC service version metadata: " + serviceName);
        }
        try {
            int parsed = Integer.parseInt(version);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw ZeroException.of(
                    DiscoveryErrorCode.DISCOVERY_CONFIGURATION_ERROR,
                    "invalid RPC service version metadata: " + version,
                    ex);
        }
    }

    private static String firstPresent(
            final Map<String, String> metadata,
            final String key,
            final String fallback) {
        String value = metadata.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ZeroException invalidMetadata(final String message) {
        return ZeroException.of(DiscoveryErrorCode.DISCOVERY_CONFIGURATION_ERROR, message, null);
    }
}
