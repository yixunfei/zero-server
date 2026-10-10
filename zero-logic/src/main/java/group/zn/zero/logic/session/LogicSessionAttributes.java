package group.zn.zero.logic.session;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 业务会话属性容器。
 *
 * @author zn
 */
public interface LogicSessionAttributes {

    /**
     * 读取属性。
     *
     * @param key 属性键；不可为空。
     * @param <T> 属性值类型。
     * @return 属性值；不可为空；可能为空；线程安全性由实现声明。
     */
    <T> Optional<T> get(LogicSessionAttributeKey<T> key);

    /**
     * 写入属性。
     *
     * @param key 属性键；不可为空。
     * @param value 属性值；不可为空。
     * @param <T> 属性值类型。
     * @return 原属性值；不可为空；可能为空；线程安全性由实现声明。
     */
    <T> Optional<T> put(LogicSessionAttributeKey<T> key, T value);

    /**
     * 移除属性。
     *
     * @param key 属性键；不可为空。
     * @param <T> 属性值类型。
     * @return 原属性值；不可为空；可能为空；线程安全性由实现声明。
     */
    <T> Optional<T> remove(LogicSessionAttributeKey<T> key);

    /**
     * 原子更新属性；updater 返回 null 删除属性，必须短时非阻塞且不得递归修改容器。
     *
     * @param key 属性键；不可为空。
     * @param updater 更新函数；不可为空；入参可能为空。
     * @param <T> 属性值类型。
     * @return 更新后的属性值；不可为空；可能为空；线程安全性由实现声明。
     */
    <T> Optional<T> update(LogicSessionAttributeKey<T> key, Function<T, T> updater);

    /**
     * 返回属性快照。
     *
     * @return 不可变、可能为空、线程安全的属性快照。
     */
    Map<String, Object> snapshot();
}
