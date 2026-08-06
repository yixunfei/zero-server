package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolMethod;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * `.si` 协议工程解析器。
 *
 * @author zn
 */
public final class SiProtocolProjectParser {

    /**
     * 协议源文件加载器。
     */
    private final ProtocolSourceLoader sourceLoader = new ProtocolSourceLoader();

    /**
     * protoId 文件解析器。
     */
    private final ProtocolIdFileParser protoIdParser = new ProtocolIdFileParser();

    /**
     * `.si` 单文件解析器。
     */
    private final SiProtocolDslParser siParser = new SiProtocolDslParser();

    /**
     * 从文件或目录解析 `.si` 协议工程。
     *
     * @param namespace Java 包名；不可为空。
     * @param inputs 输入文件或目录列表；不可为空。
     * @param protoIdPath protoId 文件；可为空。
     * @return 协议 DSL 文档；不可为空；线程安全。
     * @throws ZeroException 当加载、解析或合并失败时抛出，绑定 ErrorCode。
     */
    public ProtocolDslDocument parse(final String namespace, final List<Path> inputs, final Path protoIdPath) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(inputs, "inputs");
        List<ProtocolSourceFile> sources = sourceLoader.load(inputs);
        Map<String, ProtocolIdRange> ranges = protoIdPath == null
                ? Map.of()
                : protoIdParser.parse(protoIdPath);

        List<ProtocolDefinition> protocols = new ArrayList<>();
        List<ProtocolEnum> enums = new ArrayList<>();
        List<ProtocolMessage> messages = new ArrayList<>();
        List<ProtocolMethod> methods = new ArrayList<>();
        for (ProtocolSourceFile source : sources) {
            ProtocolDslDocument document = siParser.parse(
                    namespace,
                    source.schemaName(),
                    source.source(),
                    ranges.get(ProtocolIdFileParser.normalizeKey(source.schemaName())));
            protocols.addAll(document.protocols());
            enums.addAll(document.enums());
            messages.addAll(document.messages());
            methods.addAll(document.methods());
        }
        ProtocolDslDocument document = new ProtocolDslDocument(namespace, protocols, enums, messages, methods);
        validateConfiguredRangesUsed(ranges, sources);
        ProtocolDslValidator.validate(document);
        return document;
    }

    private void validateConfiguredRangesUsed(
            final Map<String, ProtocolIdRange> ranges,
            final List<ProtocolSourceFile> sources) {
        if (ranges.isEmpty()) {
            return;
        }
        Map<String, ProtocolSourceFile> sourceByKey = new LinkedHashMap<>();
        for (ProtocolSourceFile source : sources) {
            String key = ProtocolIdFileParser.normalizeKey(source.schemaName());
            if (sourceByKey.putIfAbsent(key, source) != null) {
                throw ZeroException.of(
                        CodegenErrorCode.DSL_VALIDATION_FAILED,
                        "duplicate schema file base name: " + source.schemaName(),
                        null);
            }
        }
        for (ProtocolIdRange range : ranges.values()) {
            if (!sourceByKey.containsKey(ProtocolIdFileParser.normalizeKey(range.schemaName()))) {
                throw ZeroException.of(
                        CodegenErrorCode.DSL_VALIDATION_FAILED,
                        "protoId schema has no matching .si file: " + range.schemaName(),
                        null);
            }
        }
    }
}
