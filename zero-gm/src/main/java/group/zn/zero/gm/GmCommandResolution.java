package group.zn.zero.gm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GM 指令注册表解析结果。
 *
 * @param definition 指令定义；不可为空。
 * @param handler 指令处理器；不可为空。
 * @param rawText 原始 DSL 文本；不可为空。
 * @param arguments 位置参数；不可为空；构造后不可变；有序；可能为空；线程安全。
 * @param namedArguments 命名参数；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @author zn
 */
public record GmCommandResolution(
        GmCommandDefinition definition,
        GmCommandHandler handler,
        String rawText,
        List<String> arguments,
        Map<String, String> namedArguments) {

    /**
     * 创建 GM 指令注册表解析结果。
     *
     * @throws NullPointerException 当定义、处理器、原始文本、位置参数或命名参数为空时抛出。
     */
    public GmCommandResolution {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(rawText, "rawText");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        namedArguments = Map.copyOf(Objects.requireNonNull(namedArguments, "namedArguments"));
    }

    /**
     * 创建执行请求。
     *
     * @param context GM 操作上下文；不可为空。
     * @return GM 指令执行请求；不可为空；无数据变更；线程安全。
     * @throws NullPointerException 当上下文为空时抛出。
     */
    public GmCommandExecutionRequest toRequest(final GmCommandContext context) {
        return new GmCommandExecutionRequest(context, definition, rawText, arguments, namedArguments);
    }
}
