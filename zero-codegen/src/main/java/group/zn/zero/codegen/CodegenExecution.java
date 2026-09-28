package group.zn.zero.codegen;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.dsl.SiProtocolProjectParser;
import group.zn.zero.codegen.generator.GeneratedOutputPlan;
import group.zn.zero.codegen.model.CodegenRequest;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.nio.file.Path;

/** 带只读模式和耗时报告的工具执行服务；CLI/GUI 共用。 */
public final class CodegenExecution {
    private CodegenExecution() {
    }

    /**
     * 解析、校验和渲染后按模式执行；线程独占，不修改协议源。
     * @param options 工程设置。
     * @param mode generate 写入，validate 校验，plan 预览，check 检查漂移，prune 清理，recover 恢复。
     * @return 不可变报告，文件列表有序且可能为空；check 漂移时退出码为 3。
     * @throws IllegalArgumentException 模式非法；解析和输出错误由统一 ZeroException 向上传递。
     */
    public static Report execute(final ProtocolCodegenOptions options, final String mode) {
        if (!Set.of("generate", "validate", "plan", "check", "prune", "recover").contains(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        long start = System.nanoTime();
        if ("recover".equals(mode)) {
            List<Path> roots = new ArrayList<>(options.languageOutputDirs().values());
            roots.addAll(options.javaArtifactOutputDirs().values());
            roots.add(options.outputDir());
            new GeneratedOutputPlan(options.outputDir(), roots).recover();
            return new Report(mode, 0, 0, 0, List.of(), 0, (System.nanoTime() - start) / 1_000_000.0);
        }
        ProtocolDslDocument document = new SiProtocolProjectParser().parse(
                options.namespace(), options.inputPaths(), options.protoIdPath());
        GeneratedOutputPlan plan = new DefaultCodeGenerator().plan(new CodegenRequest(
                document, options.outputDir(), options.languages(), options.generateBoImpl(),
                options.languageOutputDirs(), options.languageNamespaces(), options.dtoSuffixes(),
                options.javaArtifactOutputDirs(), options.javaArtifactPackages()));
        long rendered = System.nanoTime();
        List<GeneratedOutputPlan.Change> changes = Set.of("validate", "recover").contains(mode)
                ? List.of() : plan.inspect("prune".equals(mode));
        if ("generate".equals(mode) || "prune".equals(mode)) {
            plan.apply();
        }
        boolean drift = changes.stream().anyMatch(change -> "create".equals(change.status())
                || "update".equals(change.status()) || change.status().startsWith("stale"));
        return new Report(mode, "check".equals(mode) && drift ? 3 : 0,
                document.messages().size(), document.protocols().size(), changes,
                (rendered - start) / 1_000_000.0, (System.nanoTime() - rendered) / 1_000_000.0);
    }

    /** 不可变、线程安全的执行报告；毫秒耗时分离解析渲染与输出阶段。 */
    public record Report(String mode, int exitCode, int messages, int protocols,
                         List<GeneratedOutputPlan.Change> files, double renderMillis, double outputMillis) {
    }
}
