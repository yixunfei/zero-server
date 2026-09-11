package group.zn.zero.runtime.api;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;

/** Lifecycle roles shared by integration modules without a Starter dependency. */
public final class RuntimeLifecycleCapabilities {
    public static final ComponentSetKey<Lifecycle> INFRASTRUCTURE_LIFECYCLES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.INFRASTRUCTURE_LIFECYCLES, Lifecycle.class);
    public static final ComponentSetKey<Lifecycle> APPLICATION_LIFECYCLES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.APPLICATION_LIFECYCLES, Lifecycle.class);

    private RuntimeLifecycleCapabilities() {
    }
}
