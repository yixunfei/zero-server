package group.zn.zero.codegen.generator.typescript;

import group.zn.zero.codegen.generator.client.AbstractTemplateCodegenRenderer;
import group.zn.zero.codegen.generator.client.ClientCodegenSupport;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * TypeScript 客户端代码生成渲染器。
 *
 * @author zn
 */
public final class TypeScriptCodegenRenderer extends AbstractTemplateCodegenRenderer {

    /**
     * 创建 TypeScript 客户端代码生成渲染器。
     */
    public TypeScriptCodegenRenderer() {
        super("/codegen/typescript");
    }

    /**
     * 渲染 TypeScript 生成物。
     *
     * @param request 代码生成请求；不可为空。
     */
    public void render(final CodegenRequest request) {
        Objects.requireNonNull(request, "request");
        Path root = request.outputDir(CodegenLanguage.TYPESCRIPT);
        String namespace = ClientCodegenSupport.toTypeScriptNamespace(request.namespace(CodegenLanguage.TYPESCRIPT));
        String dtoSuffix = request.dtoSuffix(CodegenLanguage.TYPESCRIPT);

        writeGeneratedFile(root.resolve("zero-protocol-runtime.ts"),
                renderTemplate("runtime.ts.ftl", baseModel(namespace)));
        writeGeneratedFile(root.resolve("protocol-ids.ts"),
                renderTemplate("protocolIds.ts.ftl", protocolIdsModel(namespace, request.document().protocols())));

        List<Map<String, Object>> exports = new ArrayList<>();
        exports.add(exportModel("zero-protocol-runtime"));
        exports.add(exportModel("protocol-ids"));
        for (ProtocolEnum item : request.document().enums()) {
            String fileName = moduleName(item.name());
            writeGeneratedFile(root.resolve(fileName + ".ts"),
                    renderTemplate("enum.ts.ftl", enumModel(namespace, item)));
            exports.add(exportModel(fileName));
        }
        for (ProtocolMessage message : request.document().messages()) {
            String dtoName = dtoName(message.name(), dtoSuffix);
            String fileName = moduleName(dtoName);
            writeGeneratedFile(root.resolve(fileName + ".ts"),
                    renderTemplate("message.ts.ftl", messageModel(namespace, message, dtoSuffix)));
            exports.add(exportModel(fileName));
            String codecFileName = moduleName(dtoName + "Codec");
            writeGeneratedFile(root.resolve(codecFileName + ".ts"),
                    renderTemplate("codec.ts.ftl", codecModel(namespace, message, dtoSuffix)));
            exports.add(exportModel(codecFileName));
        }
        Map<String, Object> indexModel = baseModel(namespace);
        indexModel.put("exports", exports);
        writeGeneratedFile(root.resolve("index.ts"), renderTemplate("index.ts.ftl", indexModel));
    }

    private Map<String, Object> enumModel(final String namespace, final ProtocolEnum item) {
        Map<String, Object> model = baseModel(namespace);
        model.put("name", item.name());
        model.put("comment", commentOrDefault(item.comment(), item.name() + " 协议枚举。"));
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProtocolEnumValue value : item.values()) {
            Map<String, Object> valueModel = new LinkedHashMap<>();
            valueModel.put("name", value.name());
            valueModel.put("value", value.value());
            valueModel.put("comment", commentOrDefault(value.comment(), value.name()));
            values.add(valueModel);
        }
        model.put("values", values);
        return model;
    }

    private Map<String, Object> messageModel(
            final String namespace,
            final ProtocolMessage message,
            final String dtoSuffix) {
        Map<String, Object> model = baseModel(namespace);
        model.put("name", dtoName(message.name(), dtoSuffix));
        model.put("comment", commentOrDefault(message.comment(), message.name() + " 协议消息。"));
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> imports = new TreeSet<>();
        for (ProtocolField field : message.fields()) {
            Map<String, Object> fieldModel = new LinkedHashMap<>();
            fieldModel.put("name", field.name());
            fieldModel.put("type", tsType(field.type(), field.nullable(), dtoSuffix));
            fieldModel.put("defaultValue", tsDefaultValue(field, dtoSuffix));
            fieldModel.put("comment", commentOrDefault(field.comment(), field.name()));
            fields.add(fieldModel);
            collectImports(field.type(), imports, message.name(), dtoSuffix, false);
        }
        model.put("fields", fields);
        model.put("imports", importModels(imports));
        return model;
    }

    private Map<String, Object> protocolIdsModel(final String namespace, final List<group.zn.zero.protocol.ProtocolDefinition> protocols) {
        Map<String, Object> model = baseModel(namespace);
        List<Map<String, Object>> items = new ArrayList<>();
        int maxId = 0;
        for (group.zn.zero.protocol.ProtocolDefinition protocol : protocols) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("constantName", ClientCodegenSupport.constantName(protocol.name()));
            item.put("id", protocol.id());
            item.put("comment", protocol.name() + " 协议号。");
            items.add(item);
            maxId = Math.max(maxId, protocol.id());
        }
        model.put("items", items);
        model.put("maxId", maxId);
        return model;
    }

    private Map<String, Object> codecModel(
            final String namespace,
            final ProtocolMessage message,
            final String dtoSuffix) {
        Map<String, Object> model = baseModel(namespace);
        model.put("messageName", dtoName(message.name(), dtoSuffix));
        model.put("codecName", dtoName(message.name(), dtoSuffix) + "Codec");
        Set<String> imports = new TreeSet<>();
        imports.add(dtoName(message.name(), dtoSuffix));
        for (ProtocolField field : message.fields()) {
            collectImports(field.type(), imports, message.name(), dtoSuffix, true);
        }

        List<Map<String, Object>> writeFields = new ArrayList<>();
        List<Map<String, Object>> readFields = new ArrayList<>();
        int nullableIndex = 0;
        for (ProtocolField field : message.fields()) {
            Map<String, Object> writeField = new LinkedHashMap<>();
            writeField.put("name", field.name());
            writeField.put("nullable", field.nullable());
            writeField.put("presenceIndex", field.nullable() ? nullableIndex : -1);
            writeField.put("presentExpression", "message." + field.name() + " !== null && message." + field.name()
                    + " !== undefined");
            String writeIndent = field.nullable() ? "      " : "    ";
            writeField.put("writeCode", writeValue(field.type(), "writer", "message." + field.name(), writeIndent,
                    dtoSuffix));
            writeFields.add(writeField);

            Map<String, Object> readField = new LinkedHashMap<>();
            readField.put("name", field.name());
            readField.put("nullable", field.nullable());
            readField.put("presenceIndex", field.nullable() ? nullableIndex : -1);
            readField.put("readExpression", readValue(field.type(), "reader", dtoSuffix));
            readFields.add(readField);
            if (field.nullable()) {
                nullableIndex++;
            }
        }
        model.put("imports", importModels(imports));
        model.put("hasNullableFields", nullableIndex > 0);
        model.put("nullableFieldCount", nullableIndex);
        model.put("writeFields", writeFields);
        model.put("readFields", readFields);
        return model;
    }

    private String writeValue(
            final ProtocolType type,
            final String writer,
            final String value,
            final String indent,
            final String dtoSuffix) {
        StringBuilder builder = new StringBuilder();
        switch (type.kind()) {
            case SCALAR -> appendLine(builder, indent, writer + "." + scalarWriteMethod(type.name()) + "(" + value + ");");
            case ENUM -> appendLine(builder, indent, writer + ".writeInt(" + value + " as number);");
            case MESSAGE -> appendLine(builder, indent, dtoName(type.name(), dtoSuffix) + "Codec.write(" + writer
                    + ", " + value + ");");
            case LIST, SET -> {
                String itemWriter = childName(writer, "ItemWriter");
                String itemValue = childName(value, "ItemValue");
                appendLine(builder, indent, writer + ".writeCollection(" + value + ", (" + itemWriter + ", "
                        + itemValue + ") => {");
                builder.append(writeValue(type.arguments().get(0), itemWriter, itemValue, indent + "  ", dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case MAP -> {
                String keyWriter = childName(writer, "KeyWriter");
                String keyValue = childName(value, "KeyValue");
                String valueWriter = childName(writer, "ValueWriter");
                String mapValue = childName(value, "MapValue");
                appendLine(builder, indent, writer + ".writeMap(" + value + ", (" + keyWriter + ", " + keyValue
                        + ") => {");
                builder.append(writeValue(type.arguments().get(0), keyWriter, keyValue, indent + "  ", dtoSuffix));
                appendLine(builder, indent, "}, (" + valueWriter + ", " + mapValue + ") => {");
                builder.append(writeValue(type.arguments().get(1), valueWriter, mapValue, indent + "  ", dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case ARRAY -> {
                String itemWriter = childName(writer, "ArrayWriter");
                String itemValue = childName(value, "ArrayValue");
                appendLine(builder, indent, writer + ".writeArray(" + value + ", (" + itemWriter + ", "
                        + itemValue + ") => {");
                builder.append(writeValue(type.arguments().get(0), itemWriter, itemValue, indent + "  ", dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case OPTIONAL -> {
                appendLine(builder, indent, writer + ".writeBoolean(" + value + " !== null && " + value
                        + " !== undefined);");
                appendLine(builder, indent, "if (" + value + " !== null && " + value + " !== undefined) {");
                builder.append(writeValue(type.arguments().get(0), writer, value, indent + "  ", dtoSuffix));
                appendLine(builder, indent, "}");
            }
            case NULL -> appendLine(builder, indent, "throw new Error(\"null type is reserved\");");
        }
        return builder.toString();
    }

    private String readValue(final ProtocolType type, final String reader, final String dtoSuffix) {
        return switch (type.kind()) {
            case SCALAR -> reader + "." + scalarReadMethod(type.name()) + "()";
            case ENUM -> reader + ".readInt() as " + type.name();
            case MESSAGE -> dtoName(type.name(), dtoSuffix) + "Codec.read(" + reader + ")";
            case LIST -> reader + ".readList(" + childName(reader, "ItemReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ItemReader"), dtoSuffix) + ")";
            case SET -> reader + ".readSet(" + childName(reader, "ItemReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ItemReader"), dtoSuffix) + ")";
            case MAP -> reader + ".readMap(" + childName(reader, "KeyReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "KeyReader"), dtoSuffix) + ", "
                    + childName(reader, "ValueReader") + " => "
                    + readValue(type.arguments().get(1), childName(reader, "ValueReader"), dtoSuffix) + ")";
            case ARRAY -> reader + ".readArray(" + childName(reader, "ArrayReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ArrayReader"), dtoSuffix) + ")";
            case OPTIONAL -> "(" + reader + ".readBoolean() ? "
                    + readValue(type.arguments().get(0), reader, dtoSuffix) + " : null)";
            case NULL -> "null";
        };
    }

    private String tsType(final ProtocolType type, final boolean nullable, final String dtoSuffix) {
        String value = switch (type.kind()) {
            case SCALAR -> scalarTsType(type.name());
            case ENUM -> type.name();
            case MESSAGE -> dtoName(type.name(), dtoSuffix);
            case LIST -> "Array<" + tsType(type.arguments().get(0), false, dtoSuffix) + ">";
            case SET -> "Set<" + tsType(type.arguments().get(0), false, dtoSuffix) + ">";
            case MAP -> "Map<" + tsType(type.arguments().get(0), false, dtoSuffix) + ", "
                    + tsType(type.arguments().get(1), false, dtoSuffix) + ">";
            case ARRAY -> "Array<" + tsType(type.arguments().get(0), false, dtoSuffix) + ">";
            case OPTIONAL -> tsType(type.arguments().get(0), true, dtoSuffix);
            case NULL -> "unknown";
        };
        return nullable ? value + " | null" : value;
    }

    private String tsDefaultValue(final ProtocolField field, final String dtoSuffix) {
        if (field.nullable()) {
            return "null";
        }
        return switch (field.type().kind()) {
            case SCALAR -> scalarDefaultValue(field.type().name());
            case ENUM -> "0 as " + field.type().name();
            case MESSAGE -> "new " + dtoName(field.type().name(), dtoSuffix) + "()";
            case LIST, ARRAY -> "[]";
            case SET -> "new Set()";
            case MAP -> "new Map()";
            case OPTIONAL -> "null";
            case NULL -> "null";
        };
    }

    private String scalarTsType(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "boolean";
            case "byte", "short", "int", "uint", "id", "count", "float", "double" -> "number";
            case "long", "ulong" -> "bigint";
            case "string" -> "string";
            case "bytes" -> "Uint8Array";
            default -> "unknown";
        };
    }

    private String scalarDefaultValue(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "false";
            case "long", "ulong" -> "0n";
            case "string" -> "''";
            case "bytes" -> "new Uint8Array()";
            default -> "0";
        };
    }

    private String scalarWriteMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "writeBoolean";
            case "byte" -> "writeByte";
            case "short" -> "writeShort";
            case "int" -> "writeInt";
            case "uint", "id", "count" -> "writeUnsignedInt";
            case "long" -> "writeLong";
            case "ulong" -> "writeUnsignedLong";
            case "float" -> "writeFloat";
            case "double" -> "writeDouble";
            case "string" -> "writeString";
            case "bytes" -> "writeByteArray";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private String scalarReadMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "readBoolean";
            case "byte" -> "readByte";
            case "short" -> "readShort";
            case "int" -> "readInt";
            case "uint", "id", "count" -> "readUnsignedInt";
            case "long" -> "readLong";
            case "ulong" -> "readUnsignedLong";
            case "float" -> "readFloat";
            case "double" -> "readDouble";
            case "string" -> "readString";
            case "bytes" -> "readByteArray";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private void collectImports(
            final ProtocolType type,
            final Set<String> imports,
            final String owner,
            final String dtoSuffix,
            final boolean codecs) {
        switch (type.kind()) {
            case ENUM, MESSAGE -> {
                if (!owner.equals(type.name())) {
                    imports.add(type.kind() == group.zn.zero.codegen.model.ProtocolTypeKind.MESSAGE
                            ? dtoName(type.name(), dtoSuffix)
                            : type.name());
                }
                if (codecs && type.kind() == group.zn.zero.codegen.model.ProtocolTypeKind.MESSAGE) {
                    imports.add(dtoName(type.name(), dtoSuffix) + "Codec");
                }
            }
            case LIST, SET, ARRAY, OPTIONAL -> collectImports(type.arguments().get(0), imports, owner, dtoSuffix,
                    codecs);
            case MAP -> {
                collectImports(type.arguments().get(0), imports, owner, dtoSuffix, codecs);
                collectImports(type.arguments().get(1), imports, owner, dtoSuffix, codecs);
            }
            case SCALAR, NULL -> {
                // 标量不需要导入。
            }
        }
    }

    private List<Map<String, Object>> importModels(final Set<String> imports) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>(imports);
        unique.remove("");
        unique.remove("ZeroReader");
        unique.remove("ZeroWriter");
        for (String item : unique) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", item);
            model.put("file", moduleName(item));
            result.add(model);
        }
        return result;
    }

    private Map<String, Object> exportModel(final String file) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("file", file);
        return model;
    }

    private String dtoName(final String logicalName, final String dtoSuffix) {
        return logicalName + dtoSuffix;
    }

    private String moduleName(final String name) {
        StringBuilder builder = new StringBuilder(name.length() + 8);
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                appendSeparator(builder);
                continue;
            }
            if (Character.isUpperCase(current) && index > 0 && shouldSplitBeforeUppercase(name, index)) {
                appendSeparator(builder);
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }

    private boolean shouldSplitBeforeUppercase(final String value, final int index) {
        char previous = value.charAt(index - 1);
        if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
            return true;
        }
        return index + 1 < value.length() && Character.isLowerCase(value.charAt(index + 1));
    }

    private void appendSeparator(final StringBuilder builder) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '-') {
            builder.append('-');
        }
    }

    private String childName(final String owner, final String suffix) {
        StringBuilder builder = new StringBuilder(owner.length() + suffix.length());
        for (int index = 0; index < owner.length(); index++) {
            char value = owner.charAt(index);
            if (Character.isLetterOrDigit(value)) {
                builder.append(value);
            }
        }
        if (builder.length() == 0) {
            builder.append("value");
        }
        builder.append(suffix);
        return builder.toString();
    }

    private void appendLine(final StringBuilder builder, final String indent, final String line) {
        builder.append(indent).append(line).append(System.lineSeparator());
    }

    private String commentOrDefault(final String comment, final String defaultValue) {
        if (comment == null || comment.isBlank()) {
            return defaultValue;
        }
        return comment;
    }

    private Map<String, Object> baseModel(final String namespace) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", "Generated by zeroServer codegen");
        model.put("namespace", namespace);
        model.put("imports", List.of());
        return model;
    }
}
