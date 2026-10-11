package group.zn.zero.runtime.nacos;

import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceDiscoveryListener;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import group.zn.zero.runtime.production.RuntimeAdapterIo;
import group.zn.zero.runtime.production.RuntimeAdapterMonitoredLifecycle;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observes production Nacos operations while preserving the discovery interface. */
final class ObservedNacosDiscovery implements ServiceDiscovery {
    private final ServiceDiscovery delegate;
    private final RuntimeAdapterIo monitor;
    private final RuntimeAdapterMonitoredLifecycle lifecycle;

    /** Creates an observed discovery with the same owner as its monitored lifecycle. */
    ObservedNacosDiscovery(final ServiceDiscovery delegate, final RuntimeAdapterIo monitor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        lifecycle = new RuntimeAdapterMonitoredLifecycle(delegate, monitor);
    }

    @Override public LifecycleState state() { return lifecycle.state(); }
    @Override public void start() { lifecycle.start(); }
    @Override public void stop() { lifecycle.stop(); }

    @Override public void register(final ServiceInstance instance) {
        monitor.call(true, () -> { delegate.register(instance); return null; });
    }

    @Override public void unregister(final String serviceName, final String instanceId) {
        monitor.call(true, () -> { delegate.unregister(serviceName, instanceId); return null; });
    }

    @Override public void unregister(final String serviceName, final String groupName, final String instanceId) {
        monitor.call(true, () -> { delegate.unregister(serviceName, groupName, instanceId); return null; });
    }

    @Override public void updateHealth(final String serviceName, final String instanceId, final boolean healthy) {
        monitor.call(true, () -> { delegate.updateHealth(serviceName, instanceId, healthy); return null; });
    }

    @Override public void updateHealth(final String serviceName, final String groupName,
            final String instanceId, final boolean healthy) {
        monitor.call(true, () -> { delegate.updateHealth(serviceName, groupName, instanceId, healthy); return null; });
    }

    @Override public List<ServiceInstance> lookup(final String serviceName) {
        return monitor.call(false, () -> delegate.lookup(serviceName));
    }

    @Override public List<ServiceInstance> lookup(final ServiceQuery query) {
        return monitor.call(false, () -> delegate.lookup(query));
    }

    @Override public ServiceSubscription subscribe(final ServiceQuery query, final ServiceDiscoveryListener listener) {
        ServiceSubscription subscription = monitor.call(true, () -> delegate.subscribe(query, listener));
        return subscription::close;
    }

    @Override public void addListener(final ServiceDiscoveryListener listener) { delegate.addListener(listener); }
    @Override public Map<String, List<ServiceInstance>> snapshot() { return delegate.snapshot(); }
}
