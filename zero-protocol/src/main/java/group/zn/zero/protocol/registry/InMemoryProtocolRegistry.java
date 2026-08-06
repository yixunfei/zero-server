package group.zn.zero.protocol.registry;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的内存协议注册表。
 *
 * @author zn
 */
public final class InMemoryProtocolRegistry implements ProtocolRegistry {

    /**
     * 按协议 ID 索引。
     */
    private final Map<Integer, ProtocolDefinition> byId = new ConcurrentHashMap<>();

    /**
     * 按协议名称索引。
     */
    private final Map<String, ProtocolDefinition> byName = new ConcurrentHashMap<>();

    /**
     * 注册协议定义。
     *
     * @param definition 协议定义；不可为空。
     * @throws ZeroException 协议冲突或注册失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public void register(final ProtocolDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ProtocolDefinition existingById = byId.putIfAbsent(definition.id(), definition);
        if (existingById != null) {
            throw ZeroException.of(
                    ProtocolErrorCode.PROTOCOL_ID_CONFLICT,
                    "protocol id conflict: " + definition.id(),
                    null);
        }

        ProtocolDefinition existingByName = byName.putIfAbsent(definition.name(), definition);
        if (existingByName != null) {
            byId.remove(definition.id(), definition);
            throw ZeroException.of(
                    ProtocolErrorCode.PROTOCOL_NAME_CONFLICT,
                    "protocol name conflict: " + definition.name(),
                    null);
        }
    }

    /**
     * 根据协议 ID 查找协议。
     *
     * @param id 协议 ID。
     * @return 协议定义；为空表示不存在；线程安全。
     */
    @Override
    public Optional<ProtocolDefinition> findById(final int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * 根据协议名称查找协议。
     *
     * @param name 协议名称；不可为空。
     * @return 协议定义；为空表示不存在；线程安全。
     */
    @Override
    public Optional<ProtocolDefinition> findByName(final String name) {
        return Optional.ofNullable(byName.get(Objects.requireNonNull(name, "name")));
    }
}
