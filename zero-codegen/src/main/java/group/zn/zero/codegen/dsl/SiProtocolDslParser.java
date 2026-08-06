package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.dsl.ProtocolTypeParser.ParsedType;
import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolMethod;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFeature;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * `.si` 协议 DSL 解析器。
 *
 * @author zn
 */
public final class SiProtocolDslParser {

    /**
     * 默认客户端到服务端 ID 起始值。
     */
    private static final int DEFAULT_CLIENT_TO_SERVER_START = 1001;

    /**
     * 默认服务端到客户端 ID 起始值。
     */
    private static final int DEFAULT_SERVER_TO_CLIENT_START = 2000;

    /**
     * 解析单个 `.si` 源文件。
     *
     * @param namespace Java 包名；不可为空。
     * @param schemaName schema 基名；不可为空。
     * @param source `.si` 文本；不可为空。
     * @param configuredRange 协议 ID 区间；可为空。
     * @return 协议 DSL 文档；不可为空；线程安全。
     * @throws ZeroException DSL 解析或校验失败时抛出，绑定 ErrorCode。
     */
    public ProtocolDslDocument parse(
            final String namespace,
            final String schemaName,
            final String source,
            final ProtocolIdRange configuredRange) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(source, "source");
        PreprocessedSource preprocessed = preprocess(source);
        TopLevel topLevel = readTopLevel(preprocessed.lines());
        Set<String> enumNames = declaredNames(topLevel.blocks(), "enum");
        Set<String> messageNames = declaredNames(topLevel.blocks(), "struct");
        ProtocolTypeParser typeParser = new ProtocolTypeParser(enumNames, messageNames);

        List<ProtocolEnum> enums = new ArrayList<>();
        List<ProtocolMessage> messages = new ArrayList<>();
        List<MethodDraft> clientToServer = new ArrayList<>();
        List<MethodDraft> serverToClient = new ArrayList<>();
        for (Block block : topLevel.blocks()) {
            switch (block.kind()) {
                case "enum" -> enums.add(parseEnum(block));
                case "struct" -> messages.add(parseStruct(block, typeParser));
                case "client_to_server" -> clientToServer.addAll(parseMethods(block, typeParser));
                case "server_to_client" -> serverToClient.addAll(parseMethods(block, typeParser));
                default -> throw parseError(block.lineNumber(), "unsupported .si block: " + block.kind());
            }
        }

        ProtocolIdRange range = configuredRange == null
                ? new ProtocolIdRange(schemaName, DEFAULT_CLIENT_TO_SERVER_START, DEFAULT_SERVER_TO_CLIENT_START)
                : configuredRange;
        List<ProtocolDefinition> protocols = new ArrayList<>();
        List<ProtocolMethod> methods = new ArrayList<>();
        addProtocolMethods(schemaName, ProtocolDirection.CLIENT_TO_SERVER, clientToServer,
                normalizeClientStart(range.clientToServerStart()), protocols, messages, methods);
        addProtocolMethods(schemaName, ProtocolDirection.SERVER_TO_CLIENT, serverToClient,
                normalizeServerStart(range.serverToClientStart()), protocols, messages, methods);

        ProtocolDslDocument document = new ProtocolDslDocument(namespace, protocols, enums, messages, methods);
        ProtocolDslValidator.validate(document);
        return document;
    }

    private PreprocessedSource preprocess(final String source) {
        String normalized = source.replace('：', ':');
        String[] rawLines = normalized.split("\\R", -1);
        List<SourceLine> lines = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            String comment = "";
            int commentIndex = raw.indexOf("//");
            if (commentIndex >= 0) {
                comment = raw.substring(commentIndex + 2).trim();
                raw = raw.substring(0, commentIndex);
            }
            String text = raw.trim();
            if (text.isBlank() || text.startsWith("#")) {
                continue;
            }
            lines.add(new SourceLine(index + 1, text, comment));
        }
        return new PreprocessedSource(lines);
    }

    private TopLevel readTopLevel(final List<SourceLine> lines) {
        List<Block> blocks = new ArrayList<>();
        List<String> annotations = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            SourceLine line = lines.get(index);
            if (line.text().startsWith("@")) {
                annotations.add(line.text());
                index++;
                continue;
            }
            if (isDirectionLine(line.text())) {
                String kind = line.text().substring(0, line.text().length() - 1).trim();
                List<SourceLine> body = new ArrayList<>();
                index++;
                while (index < lines.size() && !isTopLevelStart(lines.get(index).text())) {
                    body.add(lines.get(index));
                    index++;
                }
                blocks.add(new Block(kind, kind, List.copyOf(body), List.copyOf(annotations), line.number()));
                annotations.clear();
                continue;
            }
            String kind = readNamedBlockKind(line.text());
            if (kind.isBlank()) {
                throw parseError(line.number(), "unsupported top-level declaration: " + line.text());
            }
            int brace = line.text().indexOf('{');
            if (brace < 0) {
                throw parseError(line.number(), kind + " declaration must contain '{'");
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
            blocks.add(new Block(kind, name, List.copyOf(body), List.copyOf(annotations), line.number()));
            annotations.clear();
            index++;
        }
        if (!annotations.isEmpty()) {
            throw parseError(lines.isEmpty() ? 0 : lines.get(lines.size() - 1).number(), "dangling annotations");
        }
        return new TopLevel(blocks);
    }

    private boolean isTopLevelStart(final String text) {
        return text.startsWith("@")
                || "client_to_server:".equals(text)
                || "server_to_client:".equals(text)
                || text.startsWith("enum ")
                || text.startsWith("struct ");
    }

    private boolean isDirectionLine(final String text) {
        return "client_to_server:".equals(text) || "server_to_client:".equals(text);
    }

    private String readNamedBlockKind(final String text) {
        if (text.startsWith("enum ")) {
            return "enum";
        }
        if (text.startsWith("struct ")) {
            return "struct";
        }
        return "";
    }

    private Set<String> declaredNames(final List<Block> blocks, final String kind) {
        Set<String> names = new HashSet<>();
        for (Block block : blocks) {
            if (kind.equals(block.kind()) && !names.add(block.name())) {
                throw parseError(block.lineNumber(), "duplicate " + kind + " name: " + block.name());
            }
        }
        return names;
    }

    private ProtocolEnum parseEnum(final Block block) {
        List<ProtocolEnumValue> values = new ArrayList<>();
        int nextValue = 0;
        for (SourceLine line : block.lines()) {
            String text = stripTerminator(line.text());
            if (text.isBlank()) {
                continue;
            }
            String name;
            int value;
            int assignIndex = text.indexOf('=');
            if (assignIndex >= 0) {
                name = text.substring(0, assignIndex).trim();
                value = parseInt(line.number(), text.substring(assignIndex + 1).trim(), "enum value");
            } else {
                name = text.trim();
                value = nextValue;
            }
            values.add(new ProtocolEnumValue(name, value, line.comment()));
            nextValue = value + 1;
        }
        return new ProtocolEnum(block.name(), values, String.join(" ", block.annotations()));
    }

    private ProtocolMessage parseStruct(final Block block, final ProtocolTypeParser typeParser) {
        List<ProtocolField> fields = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        int order = 1;
        for (SourceLine line : block.lines()) {
            if (line.text().startsWith("@")) {
                pendingAnnotations.add(line.text());
                continue;
            }
            String text = stripTerminator(line.text());
            int split = findLastTopLevelWhitespace(text);
            if (split <= 0 || split >= text.length() - 1) {
                throw parseError(line.number(), "struct field requires type and name");
            }
            String typeExpression = text.substring(0, split).trim();
            String fieldName = text.substring(split + 1).trim();
            FieldOptions options = readFieldOptions(pendingAnnotations);
            pendingAnnotations.clear();
            ParsedType parsedType = typeParser.parseFieldType(typeExpression);
            fields.add(new ProtocolField(
                    fieldName,
                    parsedType.type(),
                    order++,
                    parsedType.nullable() || options.nullable(),
                    options.compatible(),
                    combineComment(line.comment(), options.annotations())));
        }
        if (!pendingAnnotations.isEmpty()) {
            throw parseError(block.lineNumber(), "dangling field annotations in struct: " + block.name());
        }
        return new ProtocolMessage(block.name(), fields, String.join(" ", block.annotations()));
    }

    private List<MethodDraft> parseMethods(final Block block, final ProtocolTypeParser typeParser) {
        List<MethodDraft> methods = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        for (SourceLine line : block.lines()) {
            if (line.text().startsWith("@")) {
                pendingAnnotations.add(line.text());
                continue;
            }
            String text = stripTerminator(line.text());
            if (text.isBlank()) {
                continue;
            }
            int open = text.indexOf('(');
            int close = text.lastIndexOf(')');
            if (open <= 0 || close < open) {
                throw parseError(line.number(), "method declaration requires name and parameter list");
            }
            String methodName = text.substring(0, open).trim();
            String parameters = text.substring(open + 1, close).trim();
            List<ProtocolField> fields = parseMethodParameters(parameters, typeParser, line.number());
            methods.add(new MethodDraft(
                    methodName,
                    fields,
                    combineComment(line.comment(), pendingAnnotations),
                    line.number()));
            pendingAnnotations.clear();
        }
        if (!pendingAnnotations.isEmpty()) {
            throw parseError(block.lineNumber(), "dangling method annotations in section: " + block.kind());
        }
        return methods;
    }

    private List<ProtocolField> parseMethodParameters(
            final String parameters,
            final ProtocolTypeParser typeParser,
            final int lineNumber) {
        if (parameters.isBlank()) {
            return List.of();
        }
        List<String> tokens = splitTopLevel(parameters, ',');
        List<ProtocolField> fields = new ArrayList<>();
        int order = 1;
        for (String token : tokens) {
            int split = findLastTopLevelWhitespace(token);
            if (split <= 0 || split >= token.length() - 1) {
                throw parseError(lineNumber, "method parameter requires type and name: " + token);
            }
            String typeExpression = token.substring(0, split).trim();
            String name = token.substring(split + 1).trim();
            ParsedType parsedType = typeParser.parseFieldType(typeExpression);
            fields.add(new ProtocolField(name, parsedType.type(), order++, parsedType.nullable(), false, ""));
        }
        return fields;
    }

    private void addProtocolMethods(
            final String schemaName,
            final ProtocolDirection direction,
            final List<MethodDraft> drafts,
            final int startId,
            final List<ProtocolDefinition> protocols,
            final List<ProtocolMessage> messages,
            final List<ProtocolMethod> methods) {
        int id = startId;
        for (MethodDraft draft : drafts) {
            String messageName = toUpperCamel(schemaName) + toUpperCamel(draft.methodName()) + "Protocol";
            String eventName = messageName.substring(0, messageName.length() - "Protocol".length());
            String boName = eventName + "EventBO";
            messages.add(new ProtocolMessage(messageName, draft.parameters(), draft.comment()));
            protocols.add(new ProtocolDefinition(
                    id,
                    messageName,
                    direction,
                    1,
                    ProtocolDefinition.DEFAULT_CODEC,
                    EnumSet.noneOf(ProtocolFeature.class)));
            methods.add(new ProtocolMethod(
                    protocols.get(protocols.size() - 1),
                    messageName,
                    "",
                    boName,
                    draft.methodName(),
                    draft.comment(),
                    draft.parameters(),
                    false));
            id += 2;
        }
    }

    private int normalizeClientStart(final int value) {
        return value % 2 == 1 ? value : value + 1;
    }

    private int normalizeServerStart(final int value) {
        return value % 2 == 0 ? value : value + 1;
    }

    private FieldOptions readFieldOptions(final List<String> annotations) {
        boolean nullable = false;
        boolean compatible = false;
        for (String annotation : annotations) {
            String lower = annotation.toLowerCase(Locale.ROOT);
            nullable = nullable || lower.contains("nullable") || lower.contains("optional");
            compatible = compatible || lower.contains("compatible") || lower.contains("compat");
        }
        return new FieldOptions(nullable, compatible, List.copyOf(annotations));
    }

    private String combineComment(final String comment, final List<String> annotations) {
        if (annotations.isEmpty()) {
            return comment == null ? "" : comment;
        }
        String annotationText = String.join(" ", annotations);
        if (comment == null || comment.isBlank()) {
            return annotationText;
        }
        return comment + " " + annotationText;
    }

    private int findLastTopLevelWhitespace(final String text) {
        int depth = 0;
        int result = -1;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '<') {
                depth++;
            } else if (value == '>') {
                depth--;
            } else if (Character.isWhitespace(value) && depth == 0) {
                result = index;
            }
        }
        return result;
    }

    private List<String> splitTopLevel(final String text, final char delimiter) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '<') {
                depth++;
            } else if (value == '>') {
                depth--;
            } else if (value == delimiter && depth == 0) {
                tokens.add(text.substring(start, index).trim());
                start = index + 1;
            }
        }
        tokens.add(text.substring(start).trim());
        return tokens;
    }

    private String stripTerminator(final String text) {
        String value = text.trim();
        while (value.endsWith(";") || value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private int parseInt(final int lineNumber, final String value, final String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw parseError(lineNumber, name + " must be int: " + value, ex);
        }
    }

    private String toUpperCamel(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                builder.append(Character.toUpperCase(current));
                upperNext = false;
            } else {
                builder.append(current);
            }
        }
        if (builder.length() == 0) {
            throw parseError(0, "schema or method name can not be converted to Java class name: " + value);
        }
        return builder.toString();
    }

    private ZeroException parseError(final int lineNumber, final String message) {
        return parseError(lineNumber, message, null);
    }

    private ZeroException parseError(final int lineNumber, final String message, final Throwable cause) {
        String prefix = lineNumber <= 0 ? "" : "line " + lineNumber + ": ";
        return ZeroException.of(CodegenErrorCode.DSL_PARSE_FAILED, prefix + message, cause);
    }

    /**
     * 预处理后的源码。
     *
     * @param lines 源码行。
     */
    private record PreprocessedSource(List<SourceLine> lines) {
    }

    /**
     * 源码行。
     *
     * @param number 行号。
     * @param text 正文。
     * @param comment 行尾注释。
     */
    private record SourceLine(int number, String text, String comment) {
    }

    /**
     * 顶层块。
     *
     * @param kind 块类型。
     * @param name 块名称。
     * @param lines 块内行。
     * @param annotations 块注解。
     * @param lineNumber 块起始行号。
     */
    private record Block(String kind, String name, List<SourceLine> lines, List<String> annotations, int lineNumber) {
    }

    /**
     * 顶层文档。
     *
     * @param blocks 顶层块列表。
     */
    private record TopLevel(List<Block> blocks) {
    }

    /**
     * 方法草稿。
     *
     * @param methodName 方法名。
     * @param parameters 参数列表。
     * @param comment 注释。
     * @param lineNumber 声明行号。
     */
    private record MethodDraft(String methodName, List<ProtocolField> parameters, String comment, int lineNumber) {
    }

    /**
     * 字段选项。
     *
     * @param nullable 是否可空。
     * @param compatible 是否兼容追加字段。
     * @param annotations 原始注解。
     */
    private record FieldOptions(boolean nullable, boolean compatible, List<String> annotations) {
    }
}
