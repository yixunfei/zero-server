package group.zn.zero.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Map 配置实现测试。
 *
 * @author zn
 */
class MapZeroConfigTest {

    /**
     * 临时目录。
     */
    @TempDir
    Path tempDir;

    /**
     * 验证配置快照不可被外部 Map 修改影响。
     */
    @Test
    void configShouldKeepImmutableSnapshot() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("server.id", "s1");

        ZeroConfig config = new MapZeroConfig(source);
        source.put("server.id", "s2");

        assertEquals("s1", config.require("server.id"));
    }

    /**
     * 验证必填配置缺失时绑定统一错误码。
     */
    @Test
    void requireShouldThrowZeroExceptionWhenMissing() {
        ZeroConfig config = new MapZeroConfig(Map.of());

        ZeroException ex = assertThrows(ZeroException.class, () -> config.require("server.id"));

        assertEquals(SystemErrorCode.INVALID_ARGUMENT, ex.errorCode());
    }

    /**
     * 验证标准 properties 可以加载为统一配置。
     *
     * @throws IOException 写入临时配置文件失败时抛出。
     */
    @Test
    void loaderShouldReadPropertiesFile() throws IOException {
        Path file = tempDir.resolve("zero-server.properties");
        Files.writeString(file, "zero.discovery.mode=local\nzero.discovery.nacos.namespace=public\n");

        ZeroConfig config = ZeroConfigLoader.fromPropertiesFile(file);

        assertEquals("local", config.require("zero.discovery.mode"));
        assertEquals("public", config.require("zero.discovery.nacos.namespace"));
    }

    /**
     * 验证 properties 对象可以转换为统一配置。
     */
    @Test
    void loaderShouldReadPropertiesObject() {
        Properties properties = new Properties();
        properties.setProperty("zero.mode", "test");

        ZeroConfig config = ZeroConfigLoader.fromProperties(properties);

        assertEquals("test", config.require("zero.mode"));
    }
}
