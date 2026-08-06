package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import group.zn.zero.core.error.ZeroException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 协议字段类型表达式解析器。
 *
 * @author zn
 */
public final class ProtocolTypeParser {

    /**
     * 枚举名称集合。
     */
    private final Set<String> enumNames;

    /**
     * 消息名称集合。
     */
    private final Set<String> messageNames;

    /**
     * 当前解析表达式。
     */
    private String source;

    /**
     * 当前读取位置。
     */
    private int index;

    /**
     * 创建类型解析器。
     *
     * @param enumNames 枚举名称集合；不可为空。
     * @param messageNames 消息名称集合；不可为空。
     */
    public ProtocolTypeParser(final Set<String> enumNames, final Set<String> messageNames) {
        this.enumNames = Set.copyOf(Objects.requireNonNull(enumNames, "enumNames"));
        this.messageNames = Set.copyOf(Objects.requireNonNull(messageNames, "messageNames"));
    }

    /**
     * 解析字段类型，并把顶层 optional/nullable 归一化为字段 nullable 标记。
     *
     * @param expression 类型表达式；不可为空。
     * @return 解析后的字段类型；不可为空；线程不安全。
     * @throws ZeroException 类型表达式非法时抛出，必须绑定 ErrorCode。
     */
    public ParsedType parseFieldType(final String expression) {
        ProtocolType parsed = parse(expression);
        boolean nullable = false;
        while (parsed.kind() == ProtocolTypeKind.OPTIONAL) {
            nullable = true;
            parsed = parsed.arguments().get(0);
        }
        return new ParsedType(parsed, nullable);
    }

    /**
     * 解析嵌套类型表达式。
     *
     * @param expression 类型表达式；不可为空。
     * @return 解析后的协议类型；不可为空；线程不安全。
     * @throws ZeroException 类型表达式非法时抛出，必须绑定 ErrorCode。
     */
    public ProtocolType parse(final String expression) {
        source = Objects.requireNonNull(expression, "expression");
        index = 0;
        ProtocolType type = readType();
        skipWhitespace();
        if (index != source.length()) {
            throw parseError("unexpected trailing token");
        }
        return type;
    }

    private ProtocolType readType() {
        skipWhitespace();
        String identifier = readIdentifier();
        String normalized = identifier.toLowerCase(Locale.ROOT);
        ProtocolType type;
        if ("optional".equals(normalized)) {
            type = ProtocolType.optional(readSingleGeneric(identifier));
        } else if ("nullable".equals(normalized)) {
            type = ProtocolType.optional(readType());
        } else if (isListAlias(normalized)) {
            type = ProtocolType.list(readSingleGeneric(identifier));
        } else if (isSetAlias(normalized)) {
            type = ProtocolType.set(readSingleGeneric(identifier));
        } else if (isMapAlias(normalized)) {
            type = readMapGeneric(identifier);
        } else if ("null".equals(normalized)) {
            type = ProtocolType.nullType();
        } else if (isScalar(normalized)) {
            type = ProtocolType.scalar(normalizeScalar(normalized));
        } else if (enumNames.contains(identifier)) {
            type = ProtocolType.enumType(identifier);
        } else if (messageNames.contains(identifier)) {
            type = ProtocolType.message(identifier);
        } else {
            throw parseError("unknown custom type: " + identifier);
        }

        skipWhitespace();
        while (peek("[]")) {
            index += 2;
            type = ProtocolType.array(type);
            skipWhitespace();
        }
        if (peek("?")) {
            index++;
            type = ProtocolType.optional(type);
        }
        return type;
    }

    private ProtocolType readSingleGeneric(final String owner) {
        expect('<', owner + " requires generic type");
        ProtocolType value = readType();
        expect('>', owner + " generic type must end with '>'");
        return value;
    }

    private ProtocolType readMapGeneric(final String owner) {
        expect('<', owner + " requires generic key/value type");
        ProtocolType key = readType();
        expect(',', owner + " key/value type must be separated by ','");
        ProtocolType value = readType();
        expect('>', owner + " generic type must end with '>'");
        return ProtocolType.map(key, value);
    }

    private String readIdentifier() {
        skipWhitespace();
        if (index >= source.length()) {
            throw parseError("missing type identifier");
        }
        int start = index;
        while (index < source.length()) {
            char value = source.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '_' || value == '.') {
                index++;
                continue;
            }
            break;
        }
        if (start == index) {
            throw parseError("missing type identifier");
        }
        return source.substring(start, index);
    }

    private void expect(final char expected, final String message) {
        skipWhitespace();
        if (index >= source.length() || source.charAt(index) != expected) {
            throw parseError(message);
        }
        index++;
    }

    private boolean peek(final String token) {
        return source.startsWith(token, index);
    }

    private void skipWhitespace() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
    }

    private boolean isListAlias(final String value) {
        return "list".equals(value)
                || "arraylist".equals(value)
                || "linkedlist".equals(value)
                || "collection".equals(value);
    }

    private boolean isSetAlias(final String value) {
        return "set".equals(value)
                || "hashset".equals(value)
                || "linkedhashset".equals(value);
    }

    private boolean isMapAlias(final String value) {
        return "map".equals(value)
                || "hashmap".equals(value)
                || "linkedhashmap".equals(value)
                || "dictionary".equals(value);
    }

    private boolean isScalar(final String value) {
        return "bool".equals(value)
                || "boolean".equals(value)
                || "byte".equals(value)
                || "short".equals(value)
                || "int".equals(value)
                || "integer".equals(value)
                || "uint".equals(value)
                || "long".equals(value)
                || "ulong".equals(value)
                || "id".equals(value)
                || "count".equals(value)
                || "float".equals(value)
                || "double".equals(value)
                || "string".equals(value)
                || "bytes".equals(value);
    }

    private String normalizeScalar(final String value) {
        return switch (value) {
            case "bool" -> "boolean";
            case "integer" -> "int";
            default -> value;
        };
    }

    private ZeroException parseError(final String message) {
        return ZeroException.of(
                CodegenErrorCode.DSL_PARSE_FAILED,
                message + " at index " + index + " in type expression: " + source,
                null);
    }

    /**
     * 字段类型解析结果。
     *
     * @param type 归一化后的字段类型。
     * @param nullable 顶层字段是否允许为 null。
     * @author zn
     */
    public record ParsedType(ProtocolType type, boolean nullable) {

        /**
         * 创建字段类型解析结果。
         *
         * @throws NullPointerException 当类型为空时抛出。
         */
        public ParsedType {
            Objects.requireNonNull(type, "type");
        }
    }
}
