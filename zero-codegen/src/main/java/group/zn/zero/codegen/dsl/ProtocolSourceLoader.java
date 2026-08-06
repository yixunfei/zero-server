package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 协议源文件加载器。
 *
 * @author zn
 */
public final class ProtocolSourceLoader {

    /**
     * 加载输入路径下的所有 `.si` 协议文件。
     *
     * @param inputs 文件或目录路径列表；不可为空。
     * @return 不可变、有序、不为空、线程安全的协议源文件列表。
     * @throws ZeroException 当输入为空、路径不存在或文件读取失败时抛出，绑定 ErrorCode。
     */
    public List<ProtocolSourceFile> load(final List<Path> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "protocol input path list must not be empty",
                    null);
        }
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            collect(input, files);
        }
        files.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        if (files.isEmpty()) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "no .si protocol files found in input paths",
                    null);
        }
        List<ProtocolSourceFile> sources = new ArrayList<>();
        for (Path file : files) {
            sources.add(read(file));
        }
        return List.copyOf(sources);
    }

    private void collect(final Path input, final List<Path> files) {
        Objects.requireNonNull(input, "input");
        Path path = input.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "protocol input path does not exist: " + path,
                    null);
        }
        if (Files.isRegularFile(path)) {
            if (isSiFile(path)) {
                files.add(path);
            }
            return;
        }
        if (!Files.isDirectory(path)) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "protocol input path is not file or directory: " + path,
                    null);
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSiFile)
                    .forEach(files::add);
        } catch (IOException ex) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "failed to scan protocol input directory: " + path,
                    ex);
        }
    }

    private ProtocolSourceFile read(final Path path) {
        try {
            return new ProtocolSourceFile(
                    path,
                    stripExtension(path.getFileName().toString()),
                    Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "failed to read protocol source file: " + path,
                    ex);
        }
    }

    private boolean isSiFile(final Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".si");
    }

    private String stripExtension(final String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
