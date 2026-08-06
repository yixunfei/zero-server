package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * GM 指令注册表。
 *
 * @author zn
 */
public final class GmCommandRegistry {

    /**
     * 指令注册映射。
     */
    private final ConcurrentMap<String, GmRegisteredCommand> commands = new ConcurrentHashMap<>();

    /**
     * 注册 GM 指令。
     *
     * <p>本方法会修改注册表；注册表内部使用并发 Map 保证并发读取安全，但同一个 command key
     * 不允许重复注册。返回集合语义不涉及本方法。</p>
     *
     * @param definition GM 指令定义；不可为空。
     * @param handler GM 指令处理器；不可为空。
     * @throws NullPointerException 当定义或处理器为空时抛出。
     * @throws ZeroException 当同一指令 key 已存在时抛出。
     */
    public void register(final GmCommandDefinition definition, final GmCommandHandler handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        String commandKey = definition.commandKey();
        GmRegisteredCommand previous = commands.putIfAbsent(commandKey, new GmRegisteredCommand(definition, handler));
        if (previous != null) {
            throw ZeroException.of(GmErrorCode.COMMAND_ALREADY_REGISTERED);
        }
    }

    /**
     * 解析 DSL token 对应的已注册指令。
     *
     * <p>解析采用最长路径优先，例如已注册 `mail send` 时，`/mail send p1 i1 10`
     * 会把前三个值之外的 token 作为参数。返回参数集合有序、不可变、可能为空且线程安全；
     * 命名参数 Map 无序、不可变、可能为空且线程安全。</p>
     *
     * @param parsedCommand DSL 解析结果；不可为空。
     * @return 指令解析结果；不可为空；不会修改注册表；线程安全。
     * @throws NullPointerException 当解析结果为空时抛出。
     * @throws ZeroException 当指令不存在或参数数量不匹配时抛出。
     */
    public GmCommandResolution resolve(final GmParsedCommand parsedCommand) {
        Objects.requireNonNull(parsedCommand, "parsedCommand");
        List<String> tokens = parsedCommand.tokens();
        for (int pathSize = tokens.size(); pathSize > 0; pathSize--) {
            String commandKey = commandKeyCandidate(tokens.subList(0, pathSize));
            if (commandKey.isEmpty()) {
                continue;
            }
            GmRegisteredCommand command = commands.get(commandKey);
            if (command != null) {
                return buildResolution(parsedCommand, command, tokens.subList(pathSize, tokens.size()));
            }
        }
        throw ZeroException.of(GmErrorCode.COMMAND_NOT_FOUND);
    }

    private GmCommandResolution buildResolution(
            final GmParsedCommand parsedCommand,
            final GmRegisteredCommand command,
            final List<String> arguments) {
        GmCommandDefinition definition = command.definition();
        if (arguments.size() != definition.parameterNames().size()) {
            throw ZeroException.of(GmErrorCode.COMMAND_ARGUMENT_MISMATCH);
        }
        Map<String, String> namedArguments = new LinkedHashMap<>();
        for (int index = 0; index < arguments.size(); index++) {
            namedArguments.put(definition.parameterNames().get(index), arguments.get(index));
        }
        return new GmCommandResolution(
                definition,
                command.handler(),
                parsedCommand.rawText(),
                arguments,
                namedArguments);
    }

    private String commandKeyCandidate(final List<String> path) {
        for (String token : path) {
            if (token == null || token.isBlank() || token.indexOf('/') >= 0) {
                return "";
            }
        }
        return String.join(" ", path);
    }

    /**
     * 注册后的 GM 指令。
     *
     * @param definition 指令定义；不可为空。
     * @param handler 指令处理器；不可为空。
     */
    private record GmRegisteredCommand(GmCommandDefinition definition, GmCommandHandler handler) {

        private GmRegisteredCommand {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
