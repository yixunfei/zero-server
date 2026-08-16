package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按实际获得顺序登记、按严格逆序关闭的 build resource ledger。
 */
final class BuildResourceLedger {

    private final List<Entry> entries = new ArrayList<>();
    private final Map<AutoCloseable, Entry> identities = new IdentityHashMap<>();

    synchronized ResourceRegistrar registrar(final ComponentId owner) {
        return new ScopedRegistrar(this, Objects.requireNonNull(owner, "owner"));
    }

    synchronized RuntimeAssemblyException closeAll(final RuntimeAssemblyException original) {
        RuntimeAssemblyException primary = original;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (entry.closed) {
                continue;
            }
            try {
                entry.resource.close();
                entry.closed = true;
            } catch (Throwable failure) {
                RuntimeAssemblyException safeFailure = RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_RESOURCE_CLOSE_FAILED,
                        RuntimeFailurePhase.CLOSE,
                        entry.owner,
                        "component=" + entry.owner);
                if (primary == null) {
                    primary = safeFailure;
                } else {
                    primary.addSuppressed(safeFailure);
                }
            }
        }
        return primary;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized int pendingCount() {
        return (int) entries.stream().filter(entry -> !entry.closed).count();
    }

    private synchronized <T extends AutoCloseable> T register(
            final ComponentId owner,
            final T resource) {
        T checked = Objects.requireNonNull(resource, "resource");
        if (identities.containsKey(checked)) {
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID,
                    RuntimeFailurePhase.CREATE,
                    owner,
                    "resource=duplicate-identity");
        }
        Entry entry = new Entry(owner, checked);
        entries.add(entry);
        identities.put(checked, entry);
        return checked;
    }

    private static final class ScopedRegistrar implements ResourceRegistrar {

        private final BuildResourceLedger ledger;
        private final ComponentId owner;
        private boolean sealed;

        private ScopedRegistrar(final BuildResourceLedger ledger, final ComponentId owner) {
            this.ledger = ledger;
            this.owner = owner;
        }

        @Override
        public synchronized <T extends AutoCloseable> T register(final T resource) {
            if (sealed) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID,
                        RuntimeFailurePhase.CREATE,
                        owner,
                        "resource-registrar=sealed");
            }
            return ledger.register(owner, resource);
        }

        private synchronized void seal() {
            sealed = true;
        }
    }

    static void seal(final ResourceRegistrar registrar) {
        ((ScopedRegistrar) registrar).seal();
    }

    private static final class Entry {

        private final ComponentId owner;
        private final AutoCloseable resource;
        private boolean closed;

        private Entry(final ComponentId owner, final AutoCloseable resource) {
            this.owner = owner;
            this.resource = resource;
        }
    }
}
