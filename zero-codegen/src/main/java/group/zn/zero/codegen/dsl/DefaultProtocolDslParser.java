package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolMethod;
import group.zn.zero.codegen.dsl.ProtocolTypeParser.ParsedType;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFeature;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 默认协议 DSL 解析器。
 *
 * <pre>
 * namespace group.zn.zero.demo
 *
 * enum ItemType {
 *   UNKNOWN = 0
 *   WEAPON = 1
 * }
 *
 * message LoginRequest {
 *   field 1 int playerId
 *   field 2 nullable string token
 *   field 3 List&lt;Map&lt;int,string&gt;&gt; attrs
 * }
 *
 * protocol LoginRequest {
 *   id 1001
 *   direction client_to_server
 *   version 1
 * }
 *
 * method login {
 *   protocol LoginRequest
 *   request LoginRequest
 *   bo LoginEventBO
 * }
 * </pre>
 *
 * @author zn
 */
public final class DefaultProtocolDslParser implements ProtocolDslParser {

    /**
     * namespace 声明前缀。
     */
    private static final String NAMESPACE_PREFIX = "namespace ";

    /**
     * 字段声明前缀。
     */
    private static final String FIELD_PREFIX = "field ";

    /**
     * 解析 DSL 文本。
     *
     * @param source DSL 文本；不可为空。
     * @return 解析后的协议文档；不可为空；线程安全。
     * @throws ZeroException DSL 解析或校验失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public ProtocolDslDocument parse(final String source) {
        Objects.requireNonNull(source, "source");
        TopLevel topLevel = readTopLevel(readSourceLines(source));
        Set<String> enumNames = declaredNames(topLevel.blocks(), "enum");
        Set<String> messageNames = declaredNames(topLevel.blocks(), "message");
        ProtocolTypeParser typeParser = new ProtocolTypeParser(enumNames, messageNames);

        List<ProtocolEnum> enums = new ArrayList<>();
        List<ProtocolMessage> messages = new ArrayList<>();
        List<ProtocolDefinition> protocols = new ArrayList<>();
        List<MethodDraft> methodDrafts = new ArrayList<>();
        for (Block block : topLevel.blocks()) {
            switch (block.kind()) {
                case "enum" -> enums.add(parseEnum(block));
                case "message" -> messages.add(parseMessage(block, typeParser));
                case "protocol" -> protocols.add(parseProtocol(block));
                case "method" -> methodDrafts.add(parseMethodDraft(block));
                default -> throw parseError(block.lineNumber(), "unsupported block: " + block.kind());
            }
        }

        Map<String, ProtocolDefinition> protocolByName = new LinkedHashMap<>();
        for (ProtocolDefinition protocol : protocols) {
            if (protocolByName.putIfAbsent(protocol.name(), protocol) != null) {
                throw parseError(0, "duplicate protocol name: " + protocol.name());
            }
        }

        List<ProtocolMethod> methods = new ArrayList<>();
        for (MethodDraft draft : methodDrafts) {
            String protocolName = draft.protocolName().isBlank() ? draft.requestMessage() : draft.protocolName();
            ProtocolDefinition definition = protocolByName.get(protocolName);
            if (definition == null) {
                throw parseError(draft.lineNumber(), "unknown method protocol: " + protocolName);
            }
            methods.add(new ProtocolMethod(
                    definition,
                    draft.requestMessage(),
                    draft.responseMessage(),
                    draft.boName(),
                    draft.methodName(),
                    draft.comment()));
        }

        ProtocolDslDocument document = new ProtocolDslDocument(
                topLevel.namespace(),
                protocols,
                enums,
                messages,
                methods);
        ProtocolDslValidator.validate(document);
        return document;
    }

    private List<SourceLine> readSourceLines(final String source) {
        String[] rawLines = source.split("\\R", -1);
        List<SourceLine> lines = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            String trimmed = raw.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            int commentIndex = raw.indexOf("//");
            String comment = "";
            String content = raw;
            if (commentIndex >= 0) {
                comment = raw.substring(commentIndex + 2).trim();
                content = raw.substring(0, commentIndex);
            }
            String text = content.trim();
            if (!text.isBlank()) {
                lines.add(new SourceLine(index + 1, text, comment));
            }
        }
        return lines;
    }

    private TopLevel readTopLevel(final List<SourceLine> lines) {
        String namespace = "";
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            SourceLine line = lines.get(index);
            if (line.text().startsWith(NAMESPACE_PREFIX)) {
                if (!namespace.isBlank()) {
                    throw parseError(line.number(), "duplicate namespace declaration");
                }
                namespace = line.text().substring(NAMESPACE_PREFIX.length()).trim();
                if (namespace.isBlank()) {
                    throw parseError(line.number(), "namespace must not be blank");
                }
                index++;
                continue;
            }
            String kind = readBlockKind(line.text());
            if (kind.isBlank()) {
                throw parseError(line.number(), "unsupported top-level declaration: " + line.text());
            }
            int brace = line.text().indexOf('{');
            if (brace < 0) {
                throw parseError(line.number(), "block declaration must contain '{'");
            }
            String name = line.text().substring(kind.length(), brace).trim();
            if (name.isBlank()) {
                throw parseError(line.number(), kind + " name must not be blank");
            }
            List<SourceLine> body = new ArrayList<>();
            index++;
            while (index < lines.size() && !"}".equals(lines.get(index).text())) {
                body.add(lines.get(index));
                index++;
            }
            if (index >= lines.size()) {
                throw parseError(line.number(), "block is not closed: " + kind + " " + name);
            }
            blocks.add(new Block(kind, name, body, line.number()));
            index++;
        }
        if (namespace.isBlank()) {
            throw parseError(0, "namespace declaration is required");
        }
        return new TopLevel(namespace, blocks);
    }

    private String readBlockKind(final String text) {
        for (String kind : List.of("enum", "message", "protocol", "method")) {
            if (text.startsWith(kind + " ")) {
                return kind;
            }
        }
        return "";
    }

    private Set<String> declaredNames(final List<Block> blocks, final String kind) {
        Set<String> names = new HashSet<>();
        for (Block block : blocks) {
            if (kind.equals(block.kind())) {
                if (!names.add(block.name())) {
                    throw parseError(block.lineNumber(), "duplicate " + kind + " name: " + block.name());
                }
            }
        }
        return names;
    }

    private ProtocolEnum parseEnum(final Block block) {
        List<ProtocolEnumValue> values = new ArrayList<>();
        int nextValue = 0;
        for (SourceLine line : block.lines()) {
            String text = stripTerminator(line.text());
            String name;
            int value;
            int assignIndex = text.indexOf('=');
            if (assignIndex >= 0) {
                name = text.substring(0, assignIndex).trim();
                value = parseInt(line, text.substring(assignIndex + 1).trim(), "enum value");
            } else {
                List<String> tokens = splitTopLevelWhitespace(text);
                if (tokens.isEmpty()) {
                    throw parseError(line.number(), "empty enum value");
                }
                if (isInteger(tokens.get(0))) {
                    if (tokens.size() < 2) {
                        throw parseError(line.number(), "enum value name is missing");
                    }
                    value = parseInt(line, tokens.get(0), "enum value");
                    name = tokens.get(1);
                } else {
                    name = tokens.get(0);
                    value = nextValue;
                }
            }
            values.add(new ProtocolEnumValue(name, value, line.comment()));
            nextValue = value + 1;
        }
        return new ProtocolEnum(block.name(), values, "");
    }

    private ProtocolMessage parseMessage(final Block block, final ProtocolTypeParser typeParser) {
        List<ProtocolField> fields = new ArrayList<>();
        for (SourceLine line : block.lines()) {
            String text = stripTerminator(line.text());
            if (!text.startsWith(FIELD_PREFIX)) {
                throw parseError(line.number(), "message field must start with 'field'");
            }
            List<String> tokens = splitTopLevelWhitespace(text.substring(FIELD_PREFIX.length()).trim());
            if (tokens.size() < 3) {
                throw parseError(line.number(), "field declaration requires order, type and name");
            }
            int order = parseInt(line, tokens.get(0), "field order");
            int end = tokens.size();
            boolean nullable = false;
            boolean compatible = false;
            while (end > 2) {
                String option = tokens.get(end - 1).toLowerCase(Locale.ROOT);
                if ("nullable".equals(option) || "optional".equals(option)) {
                    nullable = true;
                    end--;
                    continue;
                }
                if ("compatible".equals(option) || "compat".equals(option)) {
                    compatible = true;
                    end--;
                    continue;
                }
                break;
            }
            if (end < 3) {
                throw parseError(line.number(), "field declaration requires type and name");
            }
            String fieldName = tokens.get(end - 1);
            String typeExpression = String.join(" ", tokens.subList(1, end - 1));
            ParsedType parsedType = typeParser.parseFieldType(typeExpression);
            fields.add(new ProtocolField(
                    fieldName,
                    parsedType.type(),
                    order,
                    nullable || parsedType.nullable(),
                    compatible,
                    line.comment()));
        }
        return new ProtocolMessage(block.name(), fields, "");
    }

    private ProtocolDefinition parseProtocol(final Block block) {
        int id = 0;
        int version = 1;
        String codecName = ProtocolDefinition.DEFAULT_CODEC;
        ProtocolDirection direction = null;
        EnumSet<ProtocolFeature> features = EnumSet.noneOf(ProtocolFeature.class);
        for (SourceLine line : block.lines()) {
            List<String> tokens = splitTopLevelWhitespace(stripTerminator(line.text()));
            if (tokens.isEmpty()) {
                continue;
            }
            String key = tokens.get(0).toLowerCase(Locale.ROOT);
            switch (key) {
                case "id" -> id = parseRequiredInt(line, tokens, "protocol id");
                case "version" -> version = parseRequiredInt(line, tokens, "protocol version");
                case "direction" -> direction = parseDirection(line, tokens);
                case "codec" -> codecName = parseRequiredValue(line, tokens, "codec");
                case "feature", "features" -> parseFeatures(line, tokens, features);
                default -> throw parseError(line.number(), "unsupported protocol property: " + key);
            }
        }
        if (id <= 0) {
            throw parseError(block.lineNumber(), "protocol id is required: " + block.name());
        }
        if (direction == null) {
            throw parseError(block.lineNumber(), "protocol direction is required: " + block.name());
        }
        return new ProtocolDefinition(id, block.name(), direction, version, codecName, features);
    }

    private MethodDraft parseMethodDraft(final Block block) {
        String protocolName = "";
        String requestMessage = "";
        String responseMessage = "";
        String boName = "";
        String comment = "";
        for (SourceLine line : block.lines()) {
            List<String> tokens = splitTopLevelWhitespace(stripTerminator(line.text()));
            if (tokens.isEmpty()) {
                continue;
            }
            String key = tokens.get(0).toLowerCase(Locale.ROOT);
            switch (key) {
                case "protocol" -> protocolName = parseRequiredValue(line, tokens, "protocol");
                case "request" -> requestMessage = parseRequiredValue(line, tokens, "request");
                case "response" -> responseMessage = normalizeResponse(parseRequiredValue(line, tokens, "response"));
                case "bo" -> boName = parseRequiredValue(line, tokens, "bo");
                case "comment" -> comment = String.join(" ", tokens.subList(1, tokens.size()));
                default -> throw parseError(line.number(), "unsupported method property: " + key);
            }
        }
        if (requestMessage.isBlank()) {
            throw parseError(block.lineNumber(), "method request is required: " + block.name());
        }
        if (boName.isBlank()) {
            throw parseError(block.lineNumber(), "method bo is required: " + block.name());
        }
        return new MethodDraft(protocolName, requestMessage, responseMessage, boName, block.name(), comment, block.lineNumber());
    }

    private int parseRequiredInt(final SourceLine line, final List<String> tokens, final String name) {
        return parseInt(line, parseRequiredValue(line, tokens, name), name);
    }

    private String parseRequiredValue(final SourceLine line, final List<String> tokens, final String name) {
        if (tokens.size() < 2) {
            throw parseError(line.number(), name + " value is required");
        }
        return tokens.get(1);
    }

    private ProtocolDirection parseDirection(final SourceLine line, final List<String> tokens) {
        String value = parseRequiredValue(line, tokens, "direction")
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return ProtocolDirection.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw parseError(line.number(), "unsupported protocol direction: " + value, ex);
        }
    }

    private void parseFeatures(
            final SourceLine line,
            final List<String> tokens,
            final EnumSet<ProtocolFeature> features) {
        if (tokens.size() < 2) {
            throw parseError(line.number(), "feature value is required");
        }
        for (int index = 1; index < tokens.size(); index++) {
            String value = tokens.get(index).replace('-', '_').toUpperCase(Locale.ROOT);
            try {
                features.add(ProtocolFeature.valueOf(value));
            } catch (IllegalArgumentException ex) {
                throw parseError(line.number(), "unsupported protocol feature: " + value, ex);
            }
        }
    }

    private String normalizeResponse(final String response) {
        if ("void".equalsIgnoreCase(response) || "none".equalsIgnoreCase(response)) {
            return "";
        }
        return response;
    }

    private String stripTerminator(final String text) {
        String value = text.trim();
        while (value.endsWith(";") || value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private List<String> splitTopLevelWhitespace(final String text) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '<') {
                depth++;
            } else if (value == '>') {
                depth--;
            }
            if (Character.isWhitespace(value) && depth == 0) {
                if (start >= 0) {
                    tokens.add(text.substring(start, index));
                    start = -1;
                }
            } else if (start < 0) {
                start = index;
            }
        }
        if (start >= 0) {
            tokens.add(text.substring(start));
        }
        return tokens;
    }

    private boolean isInteger(final String value) {
        if (value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private int parseInt(final SourceLine line, final String value, final String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw parseError(line.number(), name + " must be int: " + value, ex);
        }
    }

    private ZeroException parseError(final int lineNumber, final String message) {
        return parseError(lineNumber, message, null);
    }

    private ZeroException parseError(final int lineNumber, final String message, final Throwable cause) {
        String prefix = lineNumber <= 0 ? "" : "line " + lineNumber + ": ";
        return ZeroException.of(CodegenErrorCode.DSL_PARSE_FAILED, prefix + message, cause);
    }

    /**
     * 预处理后的源码行。
     *
     * @param number 原始行号。
     * @param text 去掉注释后的正文。
     * @param comment 行尾注释。
     */
    private record SourceLine(int number, String text, String comment) {
    }

    /**
     * 顶层块。
     *
     * @param kind 块类型。
     * @param name 块名称。
     * @param lines 块内源码行。
     * @param lineNumber 块声明行号。
     */
    private record Block(String kind, String name, List<SourceLine> lines, int lineNumber) {
    }

    /**
     * 顶层文档结构。
     *
     * @param namespace 命名空间。
     * @param blocks 顶层块列表。
     */
    private record TopLevel(String namespace, List<Block> blocks) {
    }

    /**
     * 未解析协议引用的方法声明。
     *
     * @param protocolName 协议名称。
     * @param requestMessage 请求消息名称。
     * @param responseMessage 响应消息名称。
     * @param boName 业务接口名称。
     * @param methodName 方法名称。
     * @param comment 方法注释。
     * @param lineNumber 方法声明行号。
     */
    private record MethodDraft(
            String protocolName,
            String requestMessage,
            String responseMessage,
            String boName,
            String methodName,
            String comment,
            int lineNumber) {
    }
}
