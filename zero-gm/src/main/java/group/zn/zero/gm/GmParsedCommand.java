package group.zn.zero.gm;

import java.util.List;
import java.util.Objects;

/**
 * GM DSL 解析结果。
 *
 * @param rawText 原始 DSL 文本；不可为空。
 * @param tokens 解析后的 token；不可为空；构造后不可变；有序；不可为空集合；线程安全。
 * @author zn
 */
public record GmParsedCommand(String rawText, List<String> tokens) {

    /**
     * 创建 GM DSL 解析结果。
     *
     * @throws NullPointerException 当原始文本或 token 集合为空时抛出。
     * @throws IllegalArgumentException 当 token 集合为空时抛出。
     */
    public GmParsedCommand {
        Objects.requireNonNull(rawText, "rawText");
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("tokens must not be empty");
        }
    }
}
