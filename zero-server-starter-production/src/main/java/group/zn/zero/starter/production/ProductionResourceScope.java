package group.zn.zero.starter.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Production runtime 构建期逆序资源事务。
 *
 * <p>资源创建成功后必须立即登记；构建成功后把创建顺序快照交给 runtime，构建失败则按严格逆序执行
 * best-effort 回滚。
 *
 * @author zn
 */
final class ProductionResourceScope {

    /** 已登记资源，保持创建顺序。 */
    private final List<AutoCloseable> resources = new ArrayList<>();

    /** 是否已经提交。 */
    private boolean committed;

    /**
     * 登记已创建资源。
     *
     * @param resource 资源；不可为空。
     * @param <T> 资源类型。
     * @return 原资源；不可为空，便于紧邻创建表达式登记。
     * @throws IllegalStateException scope 已提交时抛出。
     */
    <T extends AutoCloseable> T register(final T resource) {
        if (committed) {
            throw new IllegalStateException("production resource scope is committed");
        }
        resources.add(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    /**
     * 提交构建事务并返回创建顺序资源快照。
     *
     * @return 不可变、有序、可能为空且线程安全的资源快照；不可为空。
     */
    List<AutoCloseable> commit() {
        committed = true;
        return List.copyOf(resources);
    }

    /**
     * 按创建逆序回滚全部资源，并把安全关闭失败追加到主异常。
     *
     * @param primary 安全主异常；不可为空，会被原位追加 suppressed。
     */
    void rollback(final ProductionAdapterException primary) {
        ProductionAdapterException current = Objects.requireNonNull(primary, "primary");
        List<AutoCloseable> reversed = new ArrayList<>(resources);
        Collections.reverse(reversed);
        for (AutoCloseable resource : reversed) {
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                current.addSuppressed(ProductionAdapterFailures.reclassify(
                        "production-resource",
                        ProductionAdapterFailurePhase.ROLLBACK,
                        ProductionAdapterErrorCode.ROLLBACK_FAILED,
                        ProductionAdapterErrorCode.ROLLBACK_FAILED.message(),
                        closeFailure));
            }
        }
    }
}
