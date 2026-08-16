package group.zn.zero.runtime.api;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 已装配、single-use 的游戏服务器 runtime 门面。
 *
 * @author zn
 */
public interface GameRuntime extends Lifecycle, AutoCloseable {

    /**
     * 取得单值 capability。
     *
     * @param key key；不可为空。
     * @param <T> capability 类型。
     * @return capability；不可为空。
     */
    <T> T require(ComponentKey<T> key);

    /**
     * 查询可能未被当前拓扑选择的单值 capability。
     *
     * @param key key；不可为空。
     * @param <T> capability 类型。
     * @return 已绑定值；未选择 provider 时为空。
     */
    <T> Optional<T> optional(ComponentKey<T> key);

    /**
     * 取得多值 capability。
     *
     * @param key key；不可为空。
     * @param <T> capability 类型。
     * @return 不可变、有序贡献列表；不可为空。
     */
    <T> List<T> requireAll(ComponentSetKey<T> key);

    /**
     * 返回完整 runtime 状态。
     *
     * @return 状态；不可为空。
     */
    RuntimeState runtimeState();

    /**
     * 返回未创建额外组件或 resource 的装配计划。
     *
     * @return 计划；不可为空。
     */
    RuntimeAssemblyPlan plan();

    /**
     * 返回安全执行报告快照。
     *
     * @return 报告；不可为空。
     */
    RuntimeAssemblyReport report();

    /**
     * 返回已知健康结果；读取不触发新探针。
     *
     * @return 健康快照；不可为空。
     */
    RuntimeHealthSnapshot healthSnapshot();

    /**
     * 永久关闭 runtime，成功项幂等，失败清理项允许后续重试。
     */
    @Override
    void close();
}
