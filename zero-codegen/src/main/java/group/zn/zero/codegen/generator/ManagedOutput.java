package group.zn.zero.codegen.generator;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 带归属清单的工程输出生命周期，与渲染和底层事务分离。 */
final class ManagedOutput {
    /** 工程输出根目录，存放清单和恢复日志。 */
    private final Path root;
    /** 当前配置允许的全部输出根目录，包含语言和 Java 分类覆盖目录。 */
    private final List<Path> roots;

    ManagedOutput(final Path root, final List<Path> roots) {
        this.root = root.toAbsolutePath().normalize();
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }

    /** 检查并准备内容；只读取一次各个文件，后续事务只复核将要修改的文件。 */
    Prepared prepare(final Map<Path, GeneratedOutputPlan.Output> outputs, final boolean prune) {
        try {
            if (Files.exists(root.resolve(OutputTransaction.JOURNAL))) {
                throw new IOException("pending codegen transaction; run --mode recover: " + root);
            }
            String previousManifest = OutputFiles.read(root.resolve(OutputManifest.FILE));
            Map<String, String> previous = OutputManifest.read(root);
            Map<String, String> next = new LinkedHashMap<>(previous);
            List<GeneratedOutputPlan.Change> changes = new ArrayList<>();
            List<OutputTransaction.Mutation> mutations = new ArrayList<>();
            for (Map.Entry<Path, GeneratedOutputPlan.Output> entry : outputs.entrySet()) {
                Path path = entry.getKey();
                requireAllowed(path);
                GeneratedOutputPlan.Output output = entry.getValue();
                String before = OutputFiles.read(path);
                String key = key(path);
                if (output.implementation()) {
                    next.remove(key);
                    changes.add(new GeneratedOutputPlan.Change(path.toString(), before == null ? "create" : "preserve"));
                    if (before == null) {
                        mutations.add(new OutputTransaction.Mutation(path.toString(), null, output.content()));
                    }
                    continue;
                }
                if (before != null && (!before.contains(OutputFiles.MARKER)
                        || (previous.containsKey(key) && !OutputFiles.hash(before).equals(previous.get(key))))) {
                    throw new IOException("generated file has manual changes; preserve or restore before regeneration: " + path);
                }
                String status = before == null ? "create" : before.equals(output.content()) ? "unchanged" : "update";
                changes.add(new GeneratedOutputPlan.Change(path.toString(), status));
                if (!"unchanged".equals(status)) {
                    mutations.add(new OutputTransaction.Mutation(path.toString(), before, output.content()));
                }
                next.put(key, OutputFiles.hash(output.content()));
            }
            stale(outputs, previous, next, prune, changes, mutations);
            String manifest = OutputManifest.render(next);
            if (!Objects.equals(previousManifest, manifest)) {
                mutations.add(new OutputTransaction.Mutation(root.resolve(OutputManifest.FILE).toString(), previousManifest, manifest));
            }
            return new Prepared(List.copyOf(changes), List.copyOf(mutations));
        } catch (IOException ex) {
            throw failure(ex);
        }
    }

    /** 执行已准备的计划；保留事务外编辑，必要时留下恢复日志。 */
    void apply(final Prepared prepared) {
        try {
            new OutputTransaction(root, this::allowed).apply(prepared.mutations());
        } catch (IOException ex) {
            throw failure(ex);
        }
    }

    /** 使用当前输出边界恢复上次未完成的事务。 */
    void recover() {
        try {
            new OutputTransaction(root, this::allowed).recover();
        } catch (IOException ex) {
            throw failure(ex);
        }
    }

    private void stale(final Map<Path, GeneratedOutputPlan.Output> outputs,
                       final Map<String, String> previous, final Map<String, String> next, final boolean prune,
                       final List<GeneratedOutputPlan.Change> changes,
                       final List<OutputTransaction.Mutation> mutations) throws IOException {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            Path path = root.resolve(entry.getKey()).toAbsolutePath().normalize();
            if (outputs.containsKey(path)) {
                continue;
            }
            if (!allowed(path)) {
                changes.add(new GeneratedOutputPlan.Change(path.toString(), "stale-outside-config"));
                continue;
            }
            String before = OutputFiles.read(path);
            if (before == null) {
                next.remove(entry.getKey());
                continue;
            }
            boolean clean = before.contains(OutputFiles.MARKER) && OutputFiles.hash(before).equals(entry.getValue());
            changes.add(new GeneratedOutputPlan.Change(path.toString(), clean ? (prune ? "delete" : "stale") : "stale-modified"));
            if (prune && clean) {
                mutations.add(new OutputTransaction.Mutation(path.toString(), before, null));
                next.remove(entry.getKey());
            }
        }
    }

    private String key(final Path path) {
        return root.getRoot().equals(path.getRoot()) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }

    private boolean allowed(final Path path) {
        if (path.startsWith(root.resolve(".zero-codegen"))) {
            return path.equals(root.resolve(OutputManifest.FILE));
        }
        return roots.stream().anyMatch(candidate -> path.startsWith(candidate) && !path.equals(candidate));
    }

    private void requireAllowed(final Path path) throws IOException {
        if (!allowed(path)) {
            throw new IOException("output is outside configured roots: " + path);
        }
    }

    private ZeroException failure(final IOException cause) {
        return ZeroException.of(CodegenErrorCode.OUTPUT_FAILED, cause.getMessage(), cause);
    }

    /** 内容快照和用户报告；不可变有序集合。 */
    record Prepared(List<GeneratedOutputPlan.Change> changes, List<OutputTransaction.Mutation> mutations) {
    }
}
