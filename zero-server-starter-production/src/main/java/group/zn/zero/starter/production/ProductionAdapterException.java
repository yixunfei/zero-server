package group.zn.zero.starter.production;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Objects;

/**
 * 不携带第三方原始异常图的 Production Adapter 安全异常。
 *
 * <p>该异常只保存稳定 Adapter 名称、可信失败阶段、真实框架 ErrorCode 与固定安全消息。原始驱动异常不得作为
 * cause 或 suppressed 暴露；清理失败只能先转换成同类安全异常再追加。
 *
 * @author zn
 */
public final class ProductionAdapterException extends ZeroException {

    /** Adapter 稳定名称。 */
    private final String adapterName;

    /** 可信失败阶段。 */
    private final ProductionAdapterFailurePhase failurePhase;

    /**
     * 创建安全异常。
     *
     * @param adapterName Adapter 稳定名称；不可为空。
     * @param failurePhase 失败阶段；不可为空且不能为 {@code NONE}。
     * @param errorCode 真实框架 ErrorCode；不可为空。
     * @param safeMessage 固定安全消息；不可为空白，不得包含配置值。
     * @throws NullPointerException 任一必填参数为空时抛出。
     * @throws IllegalArgumentException 名称、消息为空白或阶段为 {@code NONE} 时抛出。
     */
    ProductionAdapterException(
            final String adapterName,
            final ProductionAdapterFailurePhase failurePhase,
            final ErrorCode errorCode,
            final String safeMessage) {
        super(Objects.requireNonNull(errorCode, "errorCode"), requireText(safeMessage, "safeMessage"));
        this.adapterName = requireText(adapterName, "adapterName");
        this.failurePhase = Objects.requireNonNull(failurePhase, "failurePhase");
        if (failurePhase == ProductionAdapterFailurePhase.NONE) {
            throw new IllegalArgumentException("failurePhase must describe a failure");
        }
    }

    /**
     * 返回 Adapter 稳定名称。
     *
     * @return Adapter 名称；不可为空，线程安全。
     */
    public String adapterName() {
        return adapterName;
    }

    /**
     * 返回可信失败阶段。
     *
     * @return 失败阶段；不可为空，线程安全。
     */
    public ProductionAdapterFailurePhase failurePhase() {
        return failurePhase;
    }

    /**
     * 返回不包含配置值或第三方消息的安全摘要。
     *
     * @return 安全摘要；不可为空，线程安全。
     */
    @Override
    public String toString() {
        return "ProductionAdapterException{"
                + "adapterName='" + adapterName + '\''
                + ", failurePhase=" + failurePhase
                + ", errorCode='" + code() + '\''
                + ", message='" + message() + '\''
                + '}';
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
