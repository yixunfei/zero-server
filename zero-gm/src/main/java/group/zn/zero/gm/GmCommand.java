package group.zn.zero.gm;

import java.util.Map;

/**
 * GM 指令。
 *
 * @param command 指令名。
 * @param operator 操作者。
 * @param traceId 链路追踪标识。
 * @param args 参数。
 * @author zn
 */
public record GmCommand(String command, String operator, String traceId, Map<String, String> args) {

    /**
     * 创建 GM 指令。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public GmCommand {
        java.util.Objects.requireNonNull(command, "command");
        java.util.Objects.requireNonNull(operator, "operator");
        java.util.Objects.requireNonNull(traceId, "traceId");
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}

