package group.zn.zero.codegen;

import group.zn.zero.codegen.dsl.ProtocolDslValidator;
import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.generator.GeneratedOutputPlan;
import group.zn.zero.codegen.generator.csharp.CSharpCodegenRenderer;
import group.zn.zero.codegen.generator.gdscript.GdScriptCodegenRenderer;
import group.zn.zero.codegen.generator.java.JavaCodegenRenderer;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import group.zn.zero.codegen.model.JavaArtifactKind;

/**
 * 默认代码生成器。
 *
 * @author zn
 */
public final class DefaultCodeGenerator implements CodeGenerator {

    /**
     * Java 代码生成渲染器。
     */
    private final JavaCodegenRenderer javaRenderer = new JavaCodegenRenderer();

    /**
     * C# 代码生成渲染器。
     */
    private final CSharpCodegenRenderer csharpRenderer = new CSharpCodegenRenderer();

    /**
     * TypeScript 代码生成渲染器。
     */
    private final group.zn.zero.codegen.generator.typescript.TypeScriptCodegenRenderer typescriptRenderer =
            new group.zn.zero.codegen.generator.typescript.TypeScriptCodegenRenderer();

    /**
     * GDScript 代码生成渲染器。
     */
    private final GdScriptCodegenRenderer gdscriptRenderer = new GdScriptCodegenRenderer();

    /**
     * 基于代码生成请求生成目标代码。
     *
     * @param request 代码生成请求；不可为空。
     * @throws ZeroException 生成失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public void generate(final CodegenRequest request) {
        plan(request).apply();
    }

    /**
     * 渲染全部目标供预览或执行；不写文件，实例由调用线程独占。
     * @param request 已配置的生成请求，不可为空。
     * @return 可变、有序的输出计划，包含全部目标语言。
     * @throws ZeroException 校验或模板渲染失败。
     */
    public GeneratedOutputPlan plan(final CodegenRequest request) {
        Objects.requireNonNull(request, "request");
        ProtocolDslValidator.validate(request.document());
        validateLanguages(request);
        List<Path> roots = new ArrayList<>();
        roots.add(request.outputDir());
        request.languages().forEach(language -> roots.add(request.outputDir(language)));
        if (request.languages().contains(CodegenLanguage.JAVA)) {
            for (JavaArtifactKind kind : JavaArtifactKind.values()) {
                roots.add(request.javaOutputDir(kind));
            }
        }
        GeneratedOutputPlan outputs = new GeneratedOutputPlan(request.outputDir(), roots);
        for (CodegenLanguage language : request.languages()) {
            switch (language) {
                case JAVA -> javaRenderer.render(request, outputs);
                case CSHARP -> csharpRenderer.render(request, outputs);
                case TYPESCRIPT -> typescriptRenderer.render(request, outputs);
                case GDSCRIPT -> gdscriptRenderer.render(request, outputs);
                default -> throw ZeroException.of(
                        CodegenErrorCode.UNSUPPORTED_LANGUAGE,
                        "unknown language backend: " + language,
                        null);
            }
        }
        return outputs;
    }

    /**
     * 先校验全部目标语言是否都已实现，避免写出一半后再失败。
     *
     * @param request 代码生成请求；不可为空。
     * @throws ZeroException 当存在未实现语言时抛出，必须绑定 ErrorCode。
     */
    private void validateLanguages(final CodegenRequest request) {
        for (CodegenLanguage language : request.languages()) {
            switch (language) {
                case JAVA -> {
                    // 已支持。
                }
                case CSHARP, TYPESCRIPT, GDSCRIPT -> {
                    // 已支持。
                }
                default -> throw ZeroException.of(
                        CodegenErrorCode.UNSUPPORTED_LANGUAGE,
                        "unknown language backend: " + language,
                        null);
            }
        }
    }
}
