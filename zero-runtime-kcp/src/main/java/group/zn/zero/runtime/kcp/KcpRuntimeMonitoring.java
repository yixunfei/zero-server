package group.zn.zero.runtime.kcp;

import group.zn.zero.net.kcp.KcpSnapshot;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import java.util.function.Consumer;

/**
 * 可选监控依赖的类加载边界；仅在监控模块存在时由 KCP 装配调用。
 *
 * @author zn
 */
final class KcpRuntimeMonitoring {

    private KcpRuntimeMonitoring() { }

    /** 声明可选能力以保持启动/关闭顺序；不安装监控 provider，不创建资源。 */
    static void declareDependency(final ComponentDescriptor.Builder descriptor) {
        descriptor.optional(MonitorRuntimeComponent.MONITOR_RUNTIME);
    }

    /** 仅为应用显式安装的监控 provider 创建桥接；未安装时返回 null，无采样任务。 */
    static Consumer<KcpSnapshot> create(final String name, final ComponentCreationContext context) {
        return context.optional(MonitorRuntimeComponent.MONITOR_RUNTIME)
                .map(monitor -> (Consumer<KcpSnapshot>) new KcpTelemetry(name, monitor.registry())).orElse(null);
    }
}
