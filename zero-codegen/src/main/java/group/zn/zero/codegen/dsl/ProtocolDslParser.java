package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 协议 DSL 解析器。
 *
 * @author zn
 */
public interface ProtocolDslParser {

    /**
     * 解析 DSL 文本。
     *
     * @param source DSL 文本；不可为空。
     * @return 解析后的协议文档；不可为空；线程安全性由实现声明。
     * @throws ZeroException DSL 解析或校验失败时抛出，必须绑定 ErrorCode。
     */
    ProtocolDslDocument parse(String source);

    /**
     * 从文件解析 DSL 文档。
     *
     * @param path DSL 文件路径；不可为空。
     * @return 解析后的协议文档；不可为空；线程安全性由实现声明。
     * @throws ZeroException 文件读取、DSL 解析或校验失败时抛出，必须绑定 ErrorCode。
     */
    default ProtocolDslDocument parse(final Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw ZeroException.of(
                    CodegenErrorCode.DSL_PARSE_FAILED,
                    "failed to read protocol DSL: " + path,
                    ex);
        }
    }
}
