package group.zn.zero.gm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * GM 指令定义。
 *
 * @param path 指令路径；不可为空；构造后不可变；有序；不可为空集合；线程安全。
 * @param parameterNames 参数名集合；不可为空；构造后不可变；有序；可能为空；线程安全。
 * @param description 指令说明；不可为空。
 * @param risk 风险等级；不可为空。
 * @param targetParameterName 目标对象参数名；不可为空，空字符串表示没有固定目标参数。
 * @param approvalRequired 是否要求审批。
 * @author zn
 */
public record GmCommandDefinition(
        List<String> path,
        List<String> parameterNames,
        String description,
        GmCommandRisk risk,
        String targetParameterName,
        boolean approvalRequired) {

    /**
     * 创建 GM 指令定义。
     *
     * @throws NullPointerException 当路径、参数名、说明或风险等级为空时抛出。
     * @throws IllegalArgumentException 当路径为空、路径片段非法或参数名重复时抛出。
     */
    public GmCommandDefinition {
        path = copyNonBlankList(path, "path");
        parameterNames = copyParameterNames(parameterNames);
        description = Objects.requireNonNull(description, "description");
        risk = Objects.requireNonNull(risk, "risk");
        targetParameterName = targetParameterName == null ? "" : targetParameterName;
        if (!targetParameterName.isBlank() && !parameterNames.contains(targetParameterName)) {
            throw new IllegalArgumentException("targetParameterName must exist in parameterNames");
        }
    }

    /**
     * 创建无需审批的普通 GM 指令定义。
     *
     * @param path 指令路径；不可为空；有序；不可为空集合。
     * @param parameterNames 参数名集合；不可为空；有序；可能为空。
     * @param description 指令说明；不可为空。
     * @param risk 风险等级；不可为空。
     * @param targetParameterName 目标对象参数名；可为空或空字符串。
     * @return GM 指令定义；不可为空；无数据变更；线程安全。
     * @throws NullPointerException 当路径、参数名、说明或风险等级为空时抛出。
     * @throws IllegalArgumentException 当路径为空、路径片段非法或参数名重复时抛出。
     */
    public static GmCommandDefinition of(
            final List<String> path,
            final List<String> parameterNames,
            final String description,
            final GmCommandRisk risk,
            final String targetParameterName) {
        return new GmCommandDefinition(path, parameterNames, description, risk, targetParameterName, false);
    }

    /**
     * 返回标准指令 key。
     *
     * @return 指令 key；不可为空；以空格连接路径片段；线程安全。
     */
    public String commandKey() {
        return commandKey(path);
    }

    /**
     * 根据路径生成标准指令 key。
     *
     * @param path 指令路径；不可为空；有序；不可为空集合。
     * @return 指令 key；不可为空；以空格连接路径片段；线程安全。
     * @throws NullPointerException 当路径为空时抛出。
     * @throws IllegalArgumentException 当路径为空或路径片段非法时抛出。
     */
    public static String commandKey(final List<String> path) {
        return String.join(" ", copyNonBlankList(path, "path"));
    }

    private static List<String> copyNonBlankList(final List<String> source, final String name) {
        Objects.requireNonNull(source, name);
        if (source.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        List<String> copied = new ArrayList<>(source.size());
        for (String value : source) {
            if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
                throw new IllegalArgumentException(name + " contains invalid token");
            }
            copied.add(value);
        }
        return List.copyOf(copied);
    }

    private static List<String> copyParameterNames(final List<String> source) {
        Objects.requireNonNull(source, "parameterNames");
        List<String> copied = new ArrayList<>(source.size());
        Set<String> names = new HashSet<>();
        for (String value : source) {
            if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
                throw new IllegalArgumentException("parameterNames contains invalid token");
            }
            if (!names.add(value)) {
                throw new IllegalArgumentException("parameterNames contains duplicate token");
            }
            copied.add(value);
        }
        return List.copyOf(copied);
    }
}
