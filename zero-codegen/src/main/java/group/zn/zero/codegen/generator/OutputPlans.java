package group.zn.zero.codegen.generator;

import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 显式请求到输出边界的映射；所有后端使用同一事务策略。 @author zn */
public final class OutputPlans {
    private OutputPlans() { }

    /**
     * 创建不写盘的受管计划；调用方独占使用返回实例。
     * @param request 已配置输出根的请求。
     * @return 新的可变计划，不推断公共父目录。
     */
    public static GeneratedOutputPlan forRequest(final CodegenRequest request) {
        return forLanguages(request, request.languages());
    }

    /**
     * 为公开单语言入口限制授权目录；不写盘，返回新可变计划。
     * @param request 原始请求。
     * @param language 实际渲染的语言。
     * @return 仅包含本语言及元数据根的计划。
     */
    public static GeneratedOutputPlan forLanguage(final CodegenRequest request, final CodegenLanguage language) {
        return forLanguages(request, List.of(language));
    }

    private static GeneratedOutputPlan forLanguages(final CodegenRequest request, final List<CodegenLanguage> languages) {
        List<Path> roots = new ArrayList<>();
        roots.add(request.outputDir());
        for (CodegenLanguage language : languages) roots.add(request.outputDir(language));
        if (languages.contains(CodegenLanguage.JAVA)) {
            for (JavaArtifactKind kind : JavaArtifactKind.values()) roots.add(request.javaOutputDir(kind));
        }
        return new GeneratedOutputPlan(request.outputDir(), roots);
    }
}
