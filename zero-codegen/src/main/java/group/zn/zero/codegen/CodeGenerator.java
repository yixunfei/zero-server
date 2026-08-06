package group.zn.zero.codegen;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import java.nio.file.Path;
import java.util.List;

/**
 * 代码生成器抽象。
 *
 * @author zn
 */
public interface CodeGenerator {

    /**
     * 基于代码生成请求生成目标代码。
     *
     * @param request 代码生成请求；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 生成失败时抛出，必须绑定 ErrorCode。
     */
    void generate(CodegenRequest request);

    /**
     * 基于协议 DSL 生成默认 Java 目标代码。
     *
     * @param document DSL 文档；不可为空。
     * @param outputDir 输出目录；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 生成失败时抛出，必须绑定 ErrorCode。
     */
    default void generate(final ProtocolDslDocument document, final Path outputDir) {
        generate(new CodegenRequest(document, outputDir, List.of(CodegenLanguage.JAVA), false));
    }
}
