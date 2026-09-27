package group.zn.zero.codegen.generator;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 收集一次协议生成的全部文件，并在写入前统一检查目标冲突和文件归属。
 * 调用方须串行使用同一个实例。
 *
 * @author zn
 */
public final class GeneratedOutputPlan {

    /** 按生成顺序保存文件，便于诊断并保持稳定输出。 */
    private final Map<Path, Output> outputs = new LinkedHashMap<>();

    /** 已登记目标的上级目录，用于常数时间检测文件与目录冲突。 */
    private final Set<Path> outputParents = new HashSet<>();

    /**
     * 登记工具管理的生成文件。
     *
     * @param path 目标路径，不可为空。
     * @param content UTF-8 文件内容，不可为空。
     */
    public void addGenerated(final Path path, final String content) {
        add(path, content, false);
    }

    /**
     * 登记只创建一次的业务实现模板。
     *
     * @param path 目标路径，不可为空。
     * @param content UTF-8 文件内容，不可为空。
     */
    public void addImplementation(final Path path, final String content) {
        add(path, content, true);
    }

    /**
     * 先检查所有输出，再写入；预检失败不会改变任何目标文件。
     * 写入阶段的 IO 故障仍可能留下已完成的文件，应修复故障后重新生成。
     *
     * @throws ZeroException 路径冲突、文件归属冲突或 IO 失败时抛出。
     */
    public void apply() {
        for (Map.Entry<Path, Output> entry : outputs.entrySet()) {
            if (entry.getValue().implementation()) {
                GeneratedSourceWriter.validateImplementation(entry.getKey());
            } else {
                GeneratedSourceWriter.validateGenerated(entry.getKey());
            }
        }
        for (Map.Entry<Path, Output> entry : outputs.entrySet()) {
            if (entry.getValue().implementation()) {
                GeneratedSourceWriter.createImplementation(entry.getKey(), entry.getValue().content());
            } else {
                GeneratedSourceWriter.writeGenerated(entry.getKey(), entry.getValue().content());
            }
        }
    }

    private void add(final Path path, final String content, final boolean implementation) {
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(content, "content");
        if (outputs.containsKey(target)) {
            throw failure("duplicate generated output path: " + target);
        }
        if (outputParents.contains(target)) {
            throw failure("generated output path is an output directory: " + target);
        }
        for (Path parent = target.getParent(); parent != null; parent = parent.getParent()) {
            if (outputs.containsKey(parent)) {
                throw failure("generated output parent is another output file: " + parent);
            }
        }
        for (Path parent = target.getParent(); parent != null; parent = parent.getParent()) {
            outputParents.add(parent);
        }
        outputs.put(target, new Output(content, implementation));
    }

    private static ZeroException failure(final String message) {
        return ZeroException.of(CodegenErrorCode.OUTPUT_FAILED, message, null);
    }

    /** 一项待写入文件。 */
    private record Output(String content, boolean implementation) {
    }
}
