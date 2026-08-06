package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.List;

/**
 * GM 指令 DSL 解析器。
 *
 * <p>首版 DSL 只承担命令路径与参数切分，不提供脚本、表达式或变量求值能力。</p>
 *
 * @author zn
 */
public final class GmCommandDsl {

    private GmCommandDsl() {
    }

    /**
     * 解析 GM 指令 DSL。
     *
     * <p>支持 `/mail send playerId itemId count` 形式，以及双引号包裹的空格参数。
     * 本方法不改变任何业务数据；返回 token 集合有序、不可变、不可为空集合且线程安全。</p>
     *
     * @param rawText 原始 DSL 文本；不可为空且必须以 `/` 开头。
     * @return DSL 解析结果；不可为空；无数据变更；线程安全。
     * @throws ZeroException 当 DSL 为空、缺少 `/` 前缀、引号未闭合或没有任何 token 时抛出。
     */
    public static GmParsedCommand parse(final String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw ZeroException.of(GmErrorCode.COMMAND_DSL_EMPTY);
        }
        String trimmed = rawText.trim();
        if (!trimmed.startsWith("/")) {
            throw ZeroException.of(GmErrorCode.COMMAND_DSL_INVALID);
        }
        String body = trimmed.substring(1);
        List<String> tokens = splitTokens(body);
        if (tokens.isEmpty()) {
            throw ZeroException.of(GmErrorCode.COMMAND_DSL_INVALID);
        }
        return new GmParsedCommand(trimmed, tokens);
    }

    private static List<String> splitTokens(final String body) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean escaped = false;
        boolean tokenStarted = false;
        for (int index = 0; index < body.length(); index++) {
            char value = body.charAt(index);
            if (escaped) {
                current.append(value);
                escaped = false;
                tokenStarted = true;
                continue;
            }
            if (inQuote && value == '\\') {
                escaped = true;
                tokenStarted = true;
                continue;
            }
            if (value == '"') {
                inQuote = !inQuote;
                tokenStarted = true;
                continue;
            }
            if (!inQuote && Character.isWhitespace(value)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            current.append(value);
            tokenStarted = true;
        }
        if (escaped || inQuote) {
            throw ZeroException.of(GmErrorCode.COMMAND_DSL_INVALID);
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
