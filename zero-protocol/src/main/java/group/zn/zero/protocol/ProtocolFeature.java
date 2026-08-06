package group.zn.zero.protocol;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * 协议特性位。
 *
 * <p>特性位只描述通用二进制封装能力，不表达登录、场景同步、房间、RPC 等业务语义。
 *
 * @author zn
 */
public enum ProtocolFeature {

    /**
     * payload 已压缩。
     */
    COMPRESSED(1),

    /**
     * payload 已加密。
     */
    ENCRYPTED(1 << 1),

    /**
     * payload 或扩展头已签名。
     */
    SIGNED(1 << 2),

    /**
     * frame 携带扩展头。
     */
    EXTENSION_HEADER(1 << 3);

    /**
     * 特性位掩码。
     */
    private final int mask;

    ProtocolFeature(final int mask) {
        this.mask = mask;
    }

    /**
     * 返回特性位掩码。
     *
     * @return 特性位掩码；线程安全。
     */
    public int mask() {
        return mask;
    }

    /**
     * 判断特性位是否启用。
     *
     * @param flags 特性位集合。
     * @return true 表示启用；线程安全。
     */
    public boolean enabledIn(final int flags) {
        return (flags & mask) != 0;
    }

    /**
     * 把特性集合合并为 flags。
     *
     * @param features 特性集合；不可为空。
     * @return 合并后的 flags；线程安全。
     * @throws NullPointerException 当特性集合为空时抛出。
     */
    public static int toFlags(final Collection<ProtocolFeature> features) {
        int flags = 0;
        for (ProtocolFeature feature : features) {
            flags |= feature.mask();
        }
        return flags;
    }

    /**
     * 从 flags 解析已知特性集合。
     *
     * @param flags 特性位集合。
     * @return 不可变、无序、可能为空、线程安全的特性集合。
     */
    public static Set<ProtocolFeature> fromFlags(final int flags) {
        EnumSet<ProtocolFeature> features = EnumSet.noneOf(ProtocolFeature.class);
        for (ProtocolFeature feature : values()) {
            if (feature.enabledIn(flags)) {
                features.add(feature);
            }
        }
        return Set.copyOf(features);
    }
}
