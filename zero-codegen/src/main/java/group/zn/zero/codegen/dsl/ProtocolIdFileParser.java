package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * protoId 文件解析器。
 *
 * @author zn
 */
public final class ProtocolIdFileParser {

    /**
     * 解析 protoId 文件。
     *
     * @param path protoId 文件路径；不可为空。
     * @return 不可变、有序、可能为空、线程安全的协议 ID 区间映射。
     * @throws ZeroException 当文件读取或内容解析失败时抛出，绑定 ErrorCode。
     */
    public Map<String, ProtocolIdRange> parse(final Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "failed to read protoId file: " + path,
                    ex);
        }
    }

    /**
     * 解析 protoId 文本。
     *
     * @param source protoId 文本；不可为空。
     * @return 不可变、有序、可能为空、线程安全的协议 ID 区间映射。
     * @throws ZeroException 当内容解析失败时抛出，绑定 ErrorCode。
     */
    public Map<String, ProtocolIdRange> parse(final String source) {
        Objects.requireNonNull(source, "source");
        Map<String, ProtocolIdRange> ranges = new LinkedHashMap<>();
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String text = removeInlineComment(lines[index]).trim();
            if (text.isBlank()) {
                continue;
            }
            String[] tokens = text.split("\\s+");
            if (tokens.length != 3) {
                throw parseError(index + 1, "protoId line requires schemaName c2sStart s2cStart");
            }
            ProtocolIdRange range = new ProtocolIdRange(
                    tokens[0],
                    parsePositiveInt(index + 1, tokens[1], "client-to-server start id"),
                    parsePositiveInt(index + 1, tokens[2], "server-to-client start id"));
            String key = normalizeKey(range.schemaName());
            if (ranges.putIfAbsent(key, range) != null) {
                throw parseError(index + 1, "duplicate protoId schema: " + range.schemaName());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ranges));
    }

    /**
     * 标准化 schema key。
     *
     * @param value schema 名称；不可为空。
     * @return 标准化后的 key；不可为空。
     */
    public static String normalizeKey(final String value) {
        return Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    }

    private String removeInlineComment(final String line) {
        int slash = line.indexOf("//");
        int hash = line.indexOf('#');
        int cut = -1;
        if (slash >= 0) {
            cut = slash;
        }
        if (hash >= 0 && (cut < 0 || hash < cut)) {
            cut = hash;
        }
        return cut < 0 ? line : line.substring(0, cut);
    }

    private int parsePositiveInt(final int lineNumber, final String value, final String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw parseError(lineNumber, name + " must be positive: " + value);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw parseError(lineNumber, name + " must be int: " + value, ex);
        }
    }

    private ZeroException parseError(final int lineNumber, final String message) {
        return parseError(lineNumber, message, null);
    }

    private ZeroException parseError(final int lineNumber, final String message, final Throwable cause) {
        return ZeroException.of(
                CodegenErrorCode.DSL_PARSE_FAILED,
                "line " + lineNumber + ": " + message,
                cause);
    }
}
