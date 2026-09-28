package group.zn.zero.codegen.generator;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
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

    /** 可选工程级生命周期管理器。 */
    private final ManagedOutput managed;

    /** 上次预检的内容快照，由 apply 消费。 */
    private ManagedOutput.Prepared prepared;

    /** 创建独立文件计划；用于单后端或嵌入式调用。 */
    public GeneratedOutputPlan() {
        managed = null;
    }

    /**
     * 创建具备清单、锁和恢复日志的工程计划，构造时不写文件。
     * @param root 工程输出根目录。
     * @param allowedRoots 已配置的目标目录；有序、不可为空，由构造器复制。
     */
    public GeneratedOutputPlan(final Path root, final List<Path> allowedRoots) {
        managed = new ManagedOutput(root, allowedRoots);
    }

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
     * 工程计划保存恢复日志，IO 失败自动回滚；独立文件计划仅逐文件替换。
     *
     * @throws ZeroException 路径冲突、文件归属冲突或 IO 失败时抛出。
     */
    public void apply() {
        if (managed != null) {
            managed.apply(prepared == null ? managed.prepare(outputs, false) : prepared);
            prepared = null;
            return;
        }
        inspect();
        for (Map.Entry<Path, Output> entry : outputs.entrySet()) {
            if (entry.getValue().implementation()) {
                GeneratedSourceWriter.createImplementation(entry.getKey(), entry.getValue().content());
            } else {
                GeneratedSourceWriter.writeGenerated(entry.getKey(), entry.getValue().content());
            }
        }
    }

    /**
     * 只读检查文件归属并计算变更；线程独占，不创建目录。
     * @return 不可变、有序、可能为空的状态清单。
     * @throws ZeroException 归属或 IO 检查失败。
     */
    public List<Change> inspect() {
        return inspect(false);
    }

    /**
     * 预览输出，可选列出可清理的过期文件；只读、线程独占。
     * @param prune 是否计划清理摘要一致的过期生成物。
     * @return 不可变有序状态列表，可能为空。
     */
    public List<Change> inspect(final boolean prune) {
        if (managed != null) {
            prepared = managed.prepare(outputs, prune);
            return prepared.changes();
        }
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Path, Output> entry : outputs.entrySet()) {
            if (entry.getValue().implementation()) {
                GeneratedSourceWriter.validateImplementation(entry.getKey());
            } else {
                GeneratedSourceWriter.validateGenerated(entry.getKey());
            }
            try {
                Path path = entry.getKey();
                String status = !Files.exists(path) ? "create"
                        : entry.getValue().implementation() ? "preserve"
                        : Files.readString(path).equals(entry.getValue().content()) ? "unchanged" : "update";
                changes.add(new Change(path.toString(), status));
            } catch (IOException ex) {
                throw ZeroException.of(CodegenErrorCode.OUTPUT_FAILED, "failed to inspect " + entry.getKey(), ex);
            }
        }
        return List.copyOf(changes);
    }

    /** 恢复工程未完成事务；遇到外部编辑时失败并保留恢复日志，线程独占。 */
    public void recover() {
        if (managed != null) {
            managed.recover();
            prepared = null;
        }
    }

    /** 一项只读文件变更，用于 GUI 和机器报告。 */
    public record Change(String path, String status) {
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
        outputs.put(target, new Output(content.replace("\r\n", "\n"), implementation));
        prepared = null;
    }

    private static ZeroException failure(final String message) {
        return ZeroException.of(CodegenErrorCode.OUTPUT_FAILED, message, null);
    }

    /** 一项待写入文件。 */
    record Output(String content, boolean implementation) {
    }
}
