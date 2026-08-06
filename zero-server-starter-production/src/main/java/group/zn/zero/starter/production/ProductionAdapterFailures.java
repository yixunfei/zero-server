package group.zn.zero.starter.production;

import group.zn.zero.core.error.ErrorCode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Production Adapter 异常安全边界。
 *
 * <p>本类从不复制第三方异常 message、cause、类名或 stack 内容，只保留框架可信分类并把清理失败转换为安全
 * suppressed。该工具仅用于启动与关闭冷路径。
 *
 * @author zn
 */
final class ProductionAdapterFailures {

    /** 安全 suppressed 图最大深度。 */
    private static final int MAX_SUPPRESSED_DEPTH = 8;

    private ProductionAdapterFailures() {
    }

    /**
     * 创建不带原始异常图的安全失败。
     *
     * @param adapterName Adapter 稳定名称；不可为空。
     * @param phase 失败阶段；不可为空。
     * @param errorCode 错误码；不可为空。
     * @param safeMessage 固定安全消息；不可为空。
     * @return 安全异常；不可为空，线程安全。
     */
    static ProductionAdapterException failure(
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final ErrorCode errorCode,
            final String safeMessage) {
        return new ProductionAdapterException(adapterName, phase, errorCode, safeMessage);
    }

    /**
     * 把任意异常图转换为不含原始 cause/message 的安全异常图。
     *
     * @param adapterName 无可信归因时使用的 Adapter 名称；不可为空。
     * @param phase 无可信阶段时使用的阶段；不可为空。
     * @param errorCode 无可信错误码时使用的错误码；不可为空。
     * @param safeMessage 无可信消息时使用的固定安全消息；不可为空。
     * @param source 原始失败；可为空，不会被保留。
     * @return 新建安全异常；不可为空，线程安全。
     */
    static ProductionAdapterException sanitize(
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final ErrorCode errorCode,
            final String safeMessage,
            final Throwable source) {
        return copy(
                source,
                adapterName,
                phase,
                errorCode,
                safeMessage,
                new IdentityHashMap<>(),
                0);
    }

    /**
     * 按当前补偿上下文重新分类任意失败，同时保留可信 Adapter 归因。
     *
     * <p>该入口与 {@link #sanitize(String, ProductionAdapterFailurePhase, ErrorCode, String, Throwable)}
     * 的差异是：即使来源已经是 {@link ProductionAdapterException}，也会使用调用方给出的 phase、ErrorCode
     * 和固定消息。它用于把资源自身的 close 失败准确归类为 build/start rollback 或正常 close。
     *
     * @param adapterName 无可信归因时使用的 Adapter 名称；不可为空。
     * @param phase 当前补偿阶段；不可为空。
     * @param errorCode 当前补偿错误码；不可为空。
     * @param safeMessage 当前补偿固定消息；不可为空。
     * @param source 原始失败；可为空，不会被保留。
     * @return 重新分类后的安全异常；不可为空，线程安全。
     */
    static ProductionAdapterException reclassify(
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final ErrorCode errorCode,
            final String safeMessage,
            final Throwable source) {
        String attributedAdapter = source instanceof ProductionAdapterException failure
                ? failure.adapterName()
                : Objects.requireNonNull(adapterName, "adapterName");
        return copyReclassified(
                source,
                attributedAdapter,
                Objects.requireNonNull(phase, "phase"),
                Objects.requireNonNull(errorCode, "errorCode"),
                Objects.requireNonNull(safeMessage, "safeMessage"),
                new IdentityHashMap<>(),
                0);
    }

    /**
     * 创建缺配置异常。
     *
     * @param missingKeys 缺失配置键；不可为空，可为空集合，保持调用方顺序。
     * @return 安全异常；不可为空。
     */
    static ProductionAdapterException missingConfig(final List<String> missingKeys) {
        List<String> keys = List.copyOf(Objects.requireNonNull(missingKeys, "missingKeys"));
        String message = "missing production adapter config keys: " + String.join(", ", keys);
        return failure(
                "production-runtime",
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_MISSING,
                message);
    }

    /**
     * 创建非法配置异常，只公开配置键名。
     *
     * @param adapterName Adapter 稳定名称；不可为空。
     * @param phase 解析阶段；不可为空。
     * @param key 配置键名；不可为空。
     * @return 安全异常；不可为空。
     */
    static ProductionAdapterException invalidConfig(
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final String key) {
        return failure(
                adapterName,
                phase,
                ProductionAdapterErrorCode.CONFIG_INVALID,
                "invalid production adapter config key: " + Objects.requireNonNull(key, "key"));
    }

    private static ProductionAdapterException copy(
            final Throwable source,
            final String fallbackAdapter,
            final ProductionAdapterFailurePhase fallbackPhase,
            final ErrorCode fallbackCode,
            final String fallbackMessage,
            final IdentityHashMap<Throwable, Boolean> visited,
            final int depth) {
        FailureDescriptor descriptor = descriptor(
                source, fallbackAdapter, fallbackPhase, fallbackCode, fallbackMessage);
        ProductionAdapterException safe = failure(
                descriptor.adapterName(),
                descriptor.phase(),
                descriptor.errorCode(),
                descriptor.safeMessage());
        boolean closing = descriptor.phase() == ProductionAdapterFailurePhase.CLOSE;
        ProductionAdapterFailurePhase cleanupPhase = closing
                ? ProductionAdapterFailurePhase.CLOSE
                : ProductionAdapterFailurePhase.ROLLBACK;
        ProductionAdapterErrorCode cleanupCode = closing
                ? ProductionAdapterErrorCode.CLOSE_FAILED
                : ProductionAdapterErrorCode.ROLLBACK_FAILED;
        appendReclassifiedSuppressed(
                source,
                safe,
                descriptor.adapterName(),
                cleanupPhase,
                cleanupCode,
                cleanupCode.message(),
                visited,
                depth);
        return safe;
    }

    private static FailureDescriptor descriptor(
            final Throwable source,
            final String fallbackAdapter,
            final ProductionAdapterFailurePhase fallbackPhase,
            final ErrorCode fallbackCode,
            final String fallbackMessage) {
        if (source instanceof ProductionAdapterException failure) {
            return new FailureDescriptor(
                    failure.adapterName(),
                    failure.failurePhase(),
                    failure.errorCode(),
                    failure.message());
        }
        return new FailureDescriptor(
                Objects.requireNonNull(fallbackAdapter, "fallbackAdapter"),
                Objects.requireNonNull(fallbackPhase, "fallbackPhase"),
                Objects.requireNonNull(fallbackCode, "fallbackCode"),
                Objects.requireNonNull(fallbackMessage, "fallbackMessage"));
    }

    private static ProductionAdapterException copyReclassified(
            final Throwable source,
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final ErrorCode errorCode,
            final String safeMessage,
            final IdentityHashMap<Throwable, Boolean> visited,
            final int depth) {
        String attributedAdapter = source instanceof ProductionAdapterException failure
                ? failure.adapterName()
                : adapterName;
        ProductionAdapterException safe = failure(attributedAdapter, phase, errorCode, safeMessage);
        appendReclassifiedSuppressed(
                source,
                safe,
                attributedAdapter,
                phase,
                errorCode,
                safeMessage,
                visited,
                depth);
        return safe;
    }

    private static void appendReclassifiedSuppressed(
            final Throwable source,
            final ProductionAdapterException target,
            final String adapterName,
            final ProductionAdapterFailurePhase phase,
            final ErrorCode errorCode,
            final String safeMessage,
            final IdentityHashMap<Throwable, Boolean> visited,
            final int depth) {
        if (source == null || depth >= MAX_SUPPRESSED_DEPTH || visited.put(source, Boolean.TRUE) != null) {
            return;
        }
        for (Throwable suppressed : source.getSuppressed()) {
            target.addSuppressed(copyReclassified(
                    suppressed,
                    adapterName,
                    phase,
                    errorCode,
                    safeMessage,
                    visited,
                    depth + 1));
        }
    }

    /**
     * 安全失败字段集合。
     *
     * @param adapterName Adapter 名称。
     * @param phase 失败阶段。
     * @param errorCode 错误码。
     * @param safeMessage 固定安全消息。
     * @author zn
     */
    private record FailureDescriptor(
            String adapterName,
            ProductionAdapterFailurePhase phase,
            ErrorCode errorCode,
            String safeMessage) {
    }
}
