package group.zn.zero.examples.recipes.discovery;

import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.discovery.DiscoveryRuntime;
import java.util.List;
import java.util.Map;

/**
 * 本地服务发现：注册 → 按分组/集群/健康筛选 → 下线 → 注销。
 * 地址仅作为发现元数据，不启动服务端口；不验证 Nacos 或远端可达性。
 * @author zn
 */
public final class DiscoveryExample {
    private DiscoveryExample() { }

    /** @param args 未使用；每次调用独立运行并打印，不共享状态。 */
    public static void main(final String[] args) {
        System.out.println("discovery=" + runFlow());
    }

    /**
     * 创建独占内存注册表并演示健康筛选；runtime 关闭释放服务发现组件。
     * @return 非空不可变结果，实例 ID 列表有序、可为空且线程安全。
     * @throws group.zn.zero.core.error.ZeroException 装配或生命周期失败。
     */
    public static Result runFlow() {
        var query = new ServiceQuery("logic", "game", List.of("zone-a"), true, false);
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(DiscoveryRuntime.module()).build()) {
            runtime.start();
            var discovery = runtime.require(DiscoveryRuntime.SERVICE_DISCOVERY);
            discovery.register(instance("logic-a", "zone-a", true));
            discovery.register(instance("logic-b", "zone-b", true));
            discovery.register(instance("logic-c", "zone-a", false));
            discovery.register(new ServiceInstance("logic", "observer", "127.0.0.1", 9001, "game", "zone-a",
                    true, true, true, 1.0, 1.0, Map.of("role", "observer")));
            discovery.register(new ServiceInstance("logic", "disabled", "127.0.0.1", 9002, "game", "zone-a",
                    true, false, true, 1.0, 1.0, Map.of("role", "logic")));
            var selected = selectLogic(discovery.lookup(query));
            discovery.updateHealth("logic", "game", "logic-a", false);
            int healthyAfterOffline = selectLogic(discovery.lookup(query)).size();
            discovery.updateHealth("logic", "game", "logic-c", true);
            var replacement = selectLogic(discovery.lookup(query));
            for (String id : List.of("logic-a", "logic-b", "logic-c", "observer", "disabled")) {
                discovery.unregister("logic", "game", id);
            }
            return new Result(selected, healthyAfterOffline, replacement, discovery.lookup(query).isEmpty());
        }
    }

    private static List<String> selectLogic(final List<ServiceInstance> candidates) {
        // ServiceQuery 只负责分组、集群、健康；标签与业务可选条件由调用方明确筛选。
        return candidates.stream().filter(ServiceInstance::enabled).filter(instance -> instance.weight() > 0)
                .filter(instance -> "logic".equals(instance.metadata().get("role")))
                .map(ServiceInstance::instanceId).sorted().toList();
    }

    private static ServiceInstance instance(final String id, final String cluster, final boolean healthy) {
        return new ServiceInstance("logic", id, "127.0.0.1", 9000, "game", cluster,
                healthy, true, true, 1.0, 1.0, Map.of("role", "logic"));
    }

    /**
     * 不可变筛选结果；两个 ID 列表均有序、可为空、线程安全。
     * @param selected 初次选中实例。
     * @param healthyAfterOffline 下线后的健康实例数。
     * @param replacement 后备实例恢复健康后的查询结果。
     * @param emptyAfterUnregister 注销后是否查不到实例。
     * @author zn
     */
    public record Result(List<String> selected, int healthyAfterOffline,
                         List<String> replacement, boolean emptyAfterUnregister) {
        /** 复制列表；null 列表抛出 NullPointerException，不修改输入。 */
        public Result {
            selected = List.copyOf(selected);
            replacement = List.copyOf(replacement);
        }
    }
}
