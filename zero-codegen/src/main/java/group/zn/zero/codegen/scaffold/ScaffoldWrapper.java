package group.zn.zero.codegen.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;

/** 生成器携带仓库同版本的 Maven Wrapper，生成应用不要求全局 Maven。 */
final class ScaffoldWrapper {
    /** 被脚手架清单追踪的 Wrapper 文件。 */
    static final List<String> FILES = List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");

    private ScaffoldWrapper() { }

    /** 读取构建时打包的 Wrapper；不进行网络访问或模板替换。 */
    static String read(final String path) throws IOException {
        try (var stream = ScaffoldWrapper.class.getResourceAsStream("/scaffold-wrapper/" + path)) {
            return new String(Objects.requireNonNull(stream, "bundled Maven Wrapper").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 在支持 POSIX 权限的文件系统上添加执行位；Windows 无需设置。 */
    static void executable(final Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
