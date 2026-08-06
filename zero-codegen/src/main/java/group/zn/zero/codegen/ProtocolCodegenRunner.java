package group.zn.zero.codegen;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.dsl.SiProtocolProjectParser;
import group.zn.zero.codegen.model.CodegenRequest;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 可复用的协议代码生成执行器。
 *
 * @author zn
 */
public final class ProtocolCodegenRunner {

    /**
     * `.si` 协议工程解析器。
     */
    private final SiProtocolProjectParser parser;

    /**
     * 默认代码生成器。
     */
    private final CodeGenerator generator;

    /**
     * 创建默认协议代码生成执行器。
     */
    public ProtocolCodegenRunner() {
        this(new SiProtocolProjectParser(), new DefaultCodeGenerator());
    }

    /**
     * 创建可注入解析器和生成器的协议代码生成执行器。
     *
     * @param parser `.si` 协议工程解析器；不可为空。
     * @param generator 代码生成器；不可为空。
     */
    public ProtocolCodegenRunner(final SiProtocolProjectParser parser, final CodeGenerator generator) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    /**
     * 执行协议解析与代码生成。
     *
     * @param options 工具参数；不可为空。
     * @return 解析后的 DSL 文档；不可为空；调用方只读使用，返回集合不可变。
     * @throws group.zn.zero.core.error.ZeroException 解析、校验或输出失败时抛出，并绑定 ErrorCode。
     */
    public ProtocolDslDocument run(final ProtocolCodegenOptions options) {
        return run(options, ignored -> {
        });
    }

    /**
     * 执行协议解析与代码生成，并输出进度日志。
     *
     * @param options 工具参数；不可为空。
     * @param progress 进度日志回调；可为空；回调由当前执行线程同步调用。
     * @return 解析后的 DSL 文档；不可为空；调用方只读使用，返回集合不可变。
     * @throws group.zn.zero.core.error.ZeroException 解析、校验或输出失败时抛出，并绑定 ErrorCode。
     */
    public ProtocolDslDocument run(final ProtocolCodegenOptions options, final Consumer<String> progress) {
        Objects.requireNonNull(options, "options");
        Consumer<String> logger = progress == null ? ignored -> {
        } : progress;

        logger.accept("Parsing .si protocol project...");
        ProtocolDslDocument document = parser.parse(
                options.namespace(),
                options.inputPaths(),
                options.protoIdPath());

        logger.accept("Generating protocol sources for " + options.languages() + "...");
        generator.generate(new CodegenRequest(
                document,
                options.outputDir(),
                options.languages(),
                options.generateBoImpl(),
                options.languageOutputDirs(),
                options.languageNamespaces(),
                options.dtoSuffixes(),
                options.javaArtifactOutputDirs(),
                options.javaArtifactPackages()));

        logger.accept("Generated "
                + document.messages().size() + " messages, "
                + document.protocols().size() + " protocols, "
                + document.methods().size() + " event BOs for "
                + options.languages() + " to "
                + options.outputDir().toAbsolutePath().normalize());
        return document;
    }
}
