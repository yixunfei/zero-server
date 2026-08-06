package group.zn.zero.protocol.registry;

import group.zn.zero.protocol.ProtocolDefinition;
import java.util.Optional;

/**
 * 协议注册表。
 *
 * @author zn
 */
public interface ProtocolRegistry {

    /**
     * 注册协议定义。
     *
     * @param definition 协议定义；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 协议冲突或注册失败时抛出，必须绑定 ErrorCode。
     */
    void register(ProtocolDefinition definition);

    /**
     * 根据协议 ID 查找协议。
     *
     * @param id 协议 ID。
     * @return 协议定义；为空表示不存在；线程安全性由实现声明。
     */
    Optional<ProtocolDefinition> findById(int id);

    /**
     * 根据协议名称查找协议。
     *
     * @param name 协议名称；不可为空。
     * @return 协议定义；为空表示不存在；线程安全性由实现声明。
     */
    Optional<ProtocolDefinition> findByName(String name);
}
