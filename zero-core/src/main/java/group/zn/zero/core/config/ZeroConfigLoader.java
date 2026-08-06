package group.zn.zero.core.config;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 标准外部配置加载器。
 *
 * <p>该加载器只依赖 JDK properties 格式，用于把业务项目外部配置文件转换为 `ZeroConfig`。
 * 真实敏感信息应由业务项目放在外部文件、系统属性或环境变量中，不应写入源码。
 *
 * @author zn
 */
public final class ZeroConfigLoader {

    /**
     * 标准外部配置文件系统属性。
     */
    public static final String CONFIG_FILE_PROPERTY = "zero.config.file";

    /**
     * 标准外部配置文件环境变量。
     */
    public static final String CONFIG_FILE_ENV = "ZERO_CONFIG_FILE";

    private ZeroConfigLoader() {
    }

    /**
     * 创建空配置。
     *
     * @return 空配置；不可为空；无序；线程安全。
     */
    public static ZeroConfig empty() {
        return new MapZeroConfig(Map.of());
    }

    /**
     * 从标准外部配置位置加载配置。
     *
     * <p>读取顺序为系统属性 `zero.config.file`、环境变量 `ZERO_CONFIG_FILE`。未配置路径时返回空配置。
     *
     * @return 配置对象；不可为空；无序；线程安全。
     * @throws ZeroException 当外部配置文件路径不可读或加载失败时抛出。
     */
    public static ZeroConfig loadStandard() {
        return standardConfigFile().map(ZeroConfigLoader::fromPropertiesFile).orElseGet(ZeroConfigLoader::empty);
    }

    /**
     * 从标准外部配置位置加载配置，并合并默认值。
     *
     * <p>默认值优先级低于外部配置文件。返回集合不可变、无承诺顺序、线程安全。
     *
     * @param defaults 默认配置；不可为空。
     * @return 配置对象；不可为空；无序；线程安全。
     * @throws ZeroException 当外部配置文件路径不可读或加载失败时抛出。
     */
    public static ZeroConfig loadStandard(final Map<String, String> defaults) {
        Map<String, String> merged = new LinkedHashMap<>(Objects.requireNonNull(defaults, "defaults"));
        merged.putAll(loadStandard().asMap());
        return new MapZeroConfig(merged);
    }

    /**
     * 从 properties 文件加载配置。
     *
     * @param path properties 文件路径；不可为空。
     * @return 配置对象；不可为空；无序；线程安全。
     * @throws ZeroException 当文件不可读或加载失败时抛出。
     */
    public static ZeroConfig fromPropertiesFile(final Path path) {
        Path checkedPath = Objects.requireNonNull(path, "path");
        try (Reader reader = Files.newBufferedReader(checkedPath, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return fromProperties(properties);
        } catch (IOException ex) {
            throw ZeroException.of(
                    SystemErrorCode.INVALID_ARGUMENT,
                    "failed to load zero config file: " + checkedPath,
                    ex);
        }
    }

    /**
     * 从 JDK Properties 创建配置。
     *
     * @param properties properties；不可为空。
     * @return 配置对象；不可为空；无序；线程安全。
     */
    public static ZeroConfig fromProperties(final Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return new MapZeroConfig(values);
    }

    /**
     * 返回当前标准外部配置文件路径。
     *
     * @return 配置文件路径；为空表示未配置；线程安全。
     */
    public static Optional<Path> standardConfigFile() {
        return Optional.ofNullable(System.getProperty(CONFIG_FILE_PROPERTY))
                .or(() -> Optional.ofNullable(System.getenv(CONFIG_FILE_ENV)))
                .filter(value -> !value.isBlank())
                .map(Path::of);
    }
}
