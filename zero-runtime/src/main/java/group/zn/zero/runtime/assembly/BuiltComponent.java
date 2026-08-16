package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.spi.ComponentContribution;
import java.util.Objects;

/** 已创建组件及其 contribution。 */
record BuiltComponent(ComponentId componentId, ComponentContribution contribution) {

    BuiltComponent {
        componentId = Objects.requireNonNull(componentId, "componentId");
        contribution = Objects.requireNonNull(contribution, "contribution");
    }
}
