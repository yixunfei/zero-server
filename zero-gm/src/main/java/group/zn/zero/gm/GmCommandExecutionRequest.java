package group.zn.zero.gm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * GM 指令执行请求。
 *
 * @param context 操作上下文；不可为空。
 * @param definition 指令定义；不可为空。
 * @param rawText 原始 DSL 文本；不可为空。
 * @param arguments 位置参数；不可为空；构造后不可变；有序；可能为空；线程安全。
 * @param namedArguments 命名参数；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @author zn
 */
public record GmCommandExecutionRequest(
        GmCommandContext context,
        GmCommandDefinition definition,
        String rawText,
        List<String> arguments,
        Map<String, String> namedArguments) {

    /**
     * 创建 GM 指令执行请求。
     *
     * @throws NullPointerException 当上下文、定义、原始文本、位置参数或命名参数为空时抛出。
     */
    public GmCommandExecutionRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(rawText, "rawText");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        namedArguments = Map.copyOf(Objects.requireNonNull(namedArguments, "namedArguments"));
    }

    /**
     * 返回指令 key。
     *
     * @return 指令 key；不可为空；线程安全。
     */
    public String commandKey() {
        return definition.commandKey();
    }

    /**
     * 返回审计目标对象值。
     *
     * @return 目标对象值；不可为空；当定义未声明目标参数或参数不存在时为空；线程安全。
     */
    public Optional<String> targetValue() {
        String targetParameterName = definition.targetParameterName();
        if (targetParameterName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(namedArguments.get(targetParameterName));
    }
}
