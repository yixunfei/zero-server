package group.zn.zero.runtime.spi;

import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.config.ComponentConfig;
import java.util.List;
import java.util.Optional;

/**
 * provider 创建阶段的最小权限上下文。
 *
 * @author zn
 */
public interface ComponentCreationContext {

    /**
     * 取得 descriptor 已声明的 required 单值能力。
     *
     * @param key 能力 key；不可为空。
     * @param <T> 能力类型。
     * @return 能力对象；不可为空。
     */
    <T> T require(ComponentKey<T> key);

    /**
     * 取得 descriptor 已声明的 optional 单值能力。
     *
     * @param key 能力 key；不可为空。
     * @param <T> 能力类型。
     * @return optional 对象。
     */
    <T> Optional<T> optional(ComponentKey<T> key);

    /**
     * 取得 descriptor 已声明的 required 或 optional 多值能力。
     *
     * @param key 多值能力 key；不可为空。
     * @param <T> 能力类型。
     * @return 不可变、有序贡献列表；optional 能力未选择时为空列表。
     */
    <T> List<T> requireAll(ComponentSetKey<T> key);

    /**
     * 返回当前组件自己的 typed 配置。
     *
     * @return 组件配置；不可为空。
     */
    ComponentConfig config();

    /**
     * 返回当前 create 调用的即时资源登记器。
     *
     * @return 资源登记器；不可为空。
     */
    ResourceRegistrar resources();

    /**
     * 返回 planning/create 阶段共享的累计装配截止线。
     *
     * @return deadline；不可为空。
     */
    RuntimeDeadline assemblyDeadline();
}
