package group.zn.zero.codegen.dsl;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 协议源文件。
 *
 * @param path 文件路径。
 * @param schemaName schema 基名。
 * @param source 文件内容。
 * @author zn
 */
public record ProtocolSourceFile(Path path, String schemaName, String source) {

    /**
     * 创建协议源文件。
     *
     * @throws NullPointerException 当路径、schema 基名或文件内容为空时抛出。
     * @throws IllegalArgumentException 当 schema 基名空白时抛出。
     */
    public ProtocolSourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(source, "source");
        if (schemaName.isBlank()) {
            throw new IllegalArgumentException("schemaName must not be blank");
        }
    }
}
