package group.zn.zero.starter.production;

import group.zn.zero.core.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * 单个 Adapter 的运行期诊断状态。
 *
 * <p>该对象仅保存键名、类型和错误码，不保存原始配置值；状态会在生命周期启动和健康检查阶段更新。
 *
 * @author zn
 */
final class ProductionAdapterDiagnostic {

    /**
     * Adapter 名称。
     */
    private final String adapterName;

    /**
     * 必填配置键。
     */
    private final List<String> requiredConfigKeys;

    /**
     * 已命中配置键。
     */
    private final List<String> configuredConfigKeys;

    /**
     * 缺失配置键。
     */
    private final List<String> missingConfigKeys;

    /**
     * 配置来源。
     */
    private final List<ZeroProductionConfigSource> configSources;

    /**
     * 组件类型。
     */
    private final List<String> componentTypes;

    /**
     * 当前状态。
     */
    private ZeroProductionAdapterState state;

    /**
     * 失败错误码。
     */
    private ErrorCode errorCode;

    /**
     * 可信失败阶段。
     */
    private ProductionAdapterFailurePhase failurePhase = ProductionAdapterFailurePhase.NONE;

    /**
     * 脱敏说明。
     */
    private String message = "";

    /**
     * 创建 Adapter 运行期诊断状态。
     *
     * @param adapterName Adapter 名称；不可为空。
     * @param state 初始状态；不可为空。
     * @param requiredConfigKeys 必填配置键；不可为空。
     * @param configuredConfigKeys 已命中配置键；不可为空。
     * @param missingConfigKeys 缺失配置键；不可为空。
     * @param configSources 配置来源；不可为空。
     * @param componentTypes 组件类型；不可为空。
     */
    ProductionAdapterDiagnostic(
            final String adapterName,
            final ZeroProductionAdapterState state,
            final List<String> requiredConfigKeys,
            final List<String> configuredConfigKeys,
            final List<String> missingConfigKeys,
            final List<ZeroProductionConfigSource> configSources,
            final List<String> componentTypes) {
        this.adapterName = requireText(adapterName, "adapterName");
        this.state = Objects.requireNonNull(state, "state");
        this.requiredConfigKeys = List.copyOf(Objects.requireNonNull(requiredConfigKeys, "requiredConfigKeys"));
        this.configuredConfigKeys = List.copyOf(Objects.requireNonNull(configuredConfigKeys, "configuredConfigKeys"));
        this.missingConfigKeys = List.copyOf(Objects.requireNonNull(missingConfigKeys, "missingConfigKeys"));
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
        this.componentTypes = List.copyOf(Objects.requireNonNull(componentTypes, "componentTypes"));
    }

    /**
     * 标记当前状态。
     *
     * @param nextState 新状态；不可为空。
     */
    synchronized void mark(final ZeroProductionAdapterState nextState) {
        state = Objects.requireNonNull(nextState, "nextState");
        if (nextState != ZeroProductionAdapterState.FAILED) {
            failurePhase = ProductionAdapterFailurePhase.NONE;
            errorCode = null;
            message = "";
        }
    }

    /**
     * 标记失败。
     *
     * @param failure 已经经过安全边界转换的失败；不可为空。
     */
    synchronized void fail(final ProductionAdapterException failure) {
        ProductionAdapterException current = Objects.requireNonNull(failure, "failure");
        state = ZeroProductionAdapterState.FAILED;
        failurePhase = current.failurePhase();
        errorCode = current.errorCode();
        message = current.message();
    }

    /**
     * 返回 Adapter 稳定名称。
     *
     * @return Adapter 名称；不可为空，线程安全。
     */
    String adapterName() {
        return adapterName;
    }

    /**
     * 返回不可变状态快照。
     *
     * @return Adapter 状态快照；不可为空，线程安全。
     */
    synchronized ZeroProductionAdapterStatus snapshot() {
        return new ZeroProductionAdapterStatus(
                adapterName,
                state,
                requiredConfigKeys,
                configuredConfigKeys,
                missingConfigKeys,
                configSources,
                componentTypes,
                failurePhase,
                errorCode,
                message);
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
