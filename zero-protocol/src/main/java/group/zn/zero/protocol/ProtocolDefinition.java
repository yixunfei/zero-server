package group.zn.zero.protocol;

import java.util.Objects;
import java.util.Set;

/**
 * 协议定义元数据。
 *
 * @param id 协议 ID。
 * @param name 协议名称。
 * @param direction 协议方向。
 * @param version 协议版本。
 * @param codecName 编解码器名称。
 * @param features 协议特性集合。
 * @author zn
 */
public record ProtocolDefinition(
        int id,
        String name,
        ProtocolDirection direction,
        int version,
        String codecName,
        Set<ProtocolFeature> features) {

    /**
     * 默认编解码器名称。
     */
    public static final String DEFAULT_CODEC = "zero-binary-v1";

    /**
     * 创建协议定义。
     *
     * @param id 协议 ID。
     * @param name 协议名称。
     * @param direction 协议方向。
     * @param version 协议版本。
     * @throws NullPointerException 当协议名称或方向为空时抛出。
     * @throws IllegalArgumentException 当协议 ID 或版本非法时抛出。
     */
    public ProtocolDefinition(
            final int id,
            final String name,
            final ProtocolDirection direction,
            final int version) {
        this(id, name, direction, version, DEFAULT_CODEC, Set.of());
    }

    /**
     * 创建协议定义。
     *
     * @throws NullPointerException 当协议名称、方向、编解码器名称或特性集合为空时抛出。
     * @throws IllegalArgumentException 当协议 ID 或版本非法时抛出。
     */
    public ProtocolDefinition {
        if (id <= 0) {
            throw new IllegalArgumentException("protocol id must be positive");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("protocol version must be positive");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(features, "features");
        if (name.isBlank()) {
            throw new IllegalArgumentException("protocol name must not be blank");
        }
        if (codecName.isBlank()) {
            throw new IllegalArgumentException("protocol codecName must not be blank");
        }
        features = Set.copyOf(features);
    }

    /**
     * 返回协议特性集合。
     *
     * @return 不可变、无序、可能为空、线程安全的协议特性集合。
     */
    @Override
    public Set<ProtocolFeature> features() {
        return features;
    }
}
