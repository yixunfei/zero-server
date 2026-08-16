package group.zn.zero.runtime.support;

import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用显式 provider，不做环境探测。
 */
public final class FakeRuntimeProvider implements RuntimeComponentProvider {

    private final ComponentDescriptor descriptor;
    private final Creator creator;
    private final AtomicInteger createCount = new AtomicInteger();

    public FakeRuntimeProvider(final ComponentDescriptor descriptor, final Creator creator) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.creator = Objects.requireNonNull(creator, "creator");
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) throws Exception {
        createCount.incrementAndGet();
        return creator.create(context);
    }

    public int createCount() {
        return createCount.get();
    }

    /** 可抛 checked exception 的 contribution 工厂。 */
    @FunctionalInterface
    public interface Creator {

        ComponentContribution create(ComponentCreationContext context) throws Exception;
    }
}
