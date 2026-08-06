package group.zn.zero.starter.production;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.util.Objects;

/**
 * 由 production runtime 托管的关闭型生命周期组件。
 *
 * <p>该组件启动阶段不做远程 IO，停止阶段负责关闭创建出的 driver/client；`closeNow()` 可用于 runtime 尚未
 * start 时的构建资源回收。
 *
 * @author zn
 */
final class ManagedCloseableLifecycle extends AbstractLifecycle implements AutoCloseable {

    /**
     * 组件名称。
     */
    private final String adapterName;

    /**
     * 被托管的关闭句柄。
     */
    private final AutoCloseable closeable;

    /**
     * 是否已经关闭。
     */
    private boolean closed;

    /**
     * 创建关闭型生命周期组件。
     *
     * @param adapterName Adapter 稳定名称；不可为空。
     * @param closeable 关闭句柄；不可为空。
     */
    ManagedCloseableLifecycle(final String adapterName, final AutoCloseable closeable) {
        this.adapterName = requireText(adapterName, "adapterName");
        this.closeable = Objects.requireNonNull(closeable, "closeable");
    }

    /**
     * 停止时关闭底层资源。
     */
    @Override
    protected void doStop() {
        closeNow();
    }

    /**
     * 立即关闭底层资源。
     *
     * <p>只有底层关闭真正成功后才会永久标记为 closed；失败时允许 runtime 的后续补偿关闭重试。
     * 本方法不保留第三方异常 message、cause 或 suppressed 图。
     *
     * @throws ProductionAdapterException 当关闭失败时抛出安全框架异常。
     */
    synchronized void closeNow() {
        if (closed) {
            return;
        }
        try {
            closeable.close();
            closed = true;
        } catch (Throwable failure) {
            throw ProductionAdapterFailures.sanitize(
                    adapterName,
                    ProductionAdapterFailurePhase.CLOSE,
                    ProductionAdapterErrorCode.CLOSE_FAILED,
                    ProductionAdapterErrorCode.CLOSE_FAILED.message(),
                    failure);
        }
    }

    /**
     * 立即关闭底层资源。
     */
    @Override
    public void close() {
        closeNow();
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
