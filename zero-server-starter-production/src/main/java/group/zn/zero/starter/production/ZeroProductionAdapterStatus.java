package group.zn.zero.starter.production;

import group.zn.zero.core.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * 生产 Adapter 诊断状态。
 *
 * <p>该记录用于向调用方说明 Adapter 是否启用、缺少哪些配置键、由哪些组件承载以及失败绑定的
 * ErrorCode。记录不得保存连接串、密码、token、access key 或 secret key 原始值。
 *
 * @param adapterName Adapter 名称。
 * @param state 当前装配状态。
 * @param requiredConfigKeys 必填配置键；不可变、有序、可能为空、线程安全。
 * @param configuredConfigKeys 已命中的配置键；不可变、有序、可能为空、线程安全。
 * @param missingConfigKeys 缺失配置键；不可变、有序、可能为空、线程安全。
 * @param configSources 配置来源；不可变、有序、可能为空、线程安全。
 * @param componentTypes 组件类型；不可变、有序、可能为空、线程安全。
 * @param failurePhase 失败阶段；非失败状态为 {@link ProductionAdapterFailurePhase#NONE}。
 * @param errorCode 失败 ErrorCode；为空表示无失败。
 * @param message 脱敏说明；为空表示无补充说明。
 * @author zn
 */
public record ZeroProductionAdapterStatus(
        String adapterName,
        ZeroProductionAdapterState state,
        List<String> requiredConfigKeys,
        List<String> configuredConfigKeys,
        List<String> missingConfigKeys,
        List<ZeroProductionConfigSource> configSources,
        List<String> componentTypes,
        ProductionAdapterFailurePhase failurePhase,
        ErrorCode errorCode,
        String message) {

    /**
     * 创建 Adapter 诊断状态。
     *
     * @throws NullPointerException 当必填字段为空时抛出。
     * @throws IllegalArgumentException 当 Adapter 名称为空白时抛出。
     */
    public ZeroProductionAdapterStatus {
        adapterName = requireText(adapterName, "adapterName");
        Objects.requireNonNull(state, "state");
        requiredConfigKeys = List.copyOf(Objects.requireNonNull(requiredConfigKeys, "requiredConfigKeys"));
        configuredConfigKeys = List.copyOf(Objects.requireNonNull(configuredConfigKeys, "configuredConfigKeys"));
        missingConfigKeys = List.copyOf(Objects.requireNonNull(missingConfigKeys, "missingConfigKeys"));
        configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
        componentTypes = List.copyOf(Objects.requireNonNull(componentTypes, "componentTypes"));
        Objects.requireNonNull(failurePhase, "failurePhase");
        message = message == null ? "" : message;
        if (state == ZeroProductionAdapterState.FAILED) {
            if (failurePhase == ProductionAdapterFailurePhase.NONE || errorCode == null || message.isBlank()) {
                throw new IllegalArgumentException("failed adapter status requires phase, ErrorCode and message");
            }
        } else if (failurePhase != ProductionAdapterFailurePhase.NONE || errorCode != null) {
            throw new IllegalArgumentException("non-failed adapter status must not carry failure fields");
        }
    }

    /**
     * 判断该 Adapter 是否存在缺失配置。
     *
     * @return true 表示存在缺失配置；线程安全。
     */
    public boolean missingConfig() {
        return !missingConfigKeys.isEmpty();
    }

    /**
     * 创建禁用状态。
     *
     * @param adapterName Adapter 名称；不可为空。
     * @return 禁用状态；不可为空，线程安全。
     * @throws NullPointerException Adapter 名称为空时抛出。
     * @throws IllegalArgumentException Adapter 名称为空白时抛出。
     */
    public static ZeroProductionAdapterStatus disabled(final String adapterName) {
        return new ZeroProductionAdapterStatus(
                adapterName,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ProductionAdapterFailurePhase.NONE,
                null,
                "adapter disabled");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
