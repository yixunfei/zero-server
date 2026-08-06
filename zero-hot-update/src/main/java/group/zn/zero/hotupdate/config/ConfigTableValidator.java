package group.zn.zero.hotupdate.config;

import java.util.List;
import java.util.Map;

/**
 * 完整配置表业务校验器。
 *
 * <p>输入映射不可变且保持 CSV 行顺序。实现只能校验候选数据，不能发布快照、执行文件 IO
 * 或直接修改玩家与场景 Actor 状态。
 *
 * @param <K> 配置 key 类型。
 * @param <V> 配置对象类型。
 * @author zn
 */
@FunctionalInterface
public interface ConfigTableValidator<K, V> {

    /**
     * 校验完整候选配置表。
     *
     * @param values 候选配置映射；不可变、有序、可能为空、非线程安全共享。
     * @return 校验问题列表；不可为空、按返回顺序输出、可能为空。
     * @throws Exception 当校验器自身无法完成校验时抛出；调用方会拒绝整表。
     */
    List<ConfigValidationIssue> validate(Map<K, V> values) throws Exception;

    /**
     * 返回不产生问题的校验器。
     *
     * @param <K> 配置 key 类型。
     * @param <V> 配置对象类型。
     * @return 无操作校验器；不可为空；线程安全。
     */
    static <K, V> ConfigTableValidator<K, V> noOp() {
        return values -> List.of();
    }
}
