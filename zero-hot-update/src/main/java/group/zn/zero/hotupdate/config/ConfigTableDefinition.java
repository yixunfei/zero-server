package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ZeroException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 单个 CSV 配置表定义。
 *
 * @param <K> 配置 key 类型。
 * @param <V> 配置对象类型。
 * @param tableName 稳定表名；不可为空白，只应用作低基数标识。
 * @param source CSV 文件；不可为空，构造后转为绝对规范路径。
 * @param keyColumn key 列名；不可为空白。
 * @param requiredColumns 必需列集合；不可为空，构造后复制且自动包含 key 列。
 * @param keyDecoder key 文本转换器；不可为空。
 * @param rowDecoder 行转换器；不可为空。
 * @param validator 完整表校验器；不可为空。
 * @author zn
 */
public record ConfigTableDefinition<K, V>(
        String tableName,
        Path source,
        String keyColumn,
        Set<String> requiredColumns,
        ConfigValueDecoder<K> keyDecoder,
        CsvRowDecoder<V> rowDecoder,
        ConfigTableValidator<K, V> validator) {

    /**
     * 配置表定义标准化构造器。
     *
     * @throws ZeroException 当表名、列名或必需列非法时抛出。
     * @throws NullPointerException 当路径、decoder 或 validator 为空时抛出。
     */
    public ConfigTableDefinition {
        tableName = requireText(tableName, "tableName");
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        keyColumn = requireText(keyColumn, "keyColumn");
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        Objects.requireNonNull(requiredColumns, "requiredColumns")
                .forEach(column -> columns.add(requireText(column, "requiredColumn")));
        columns.add(keyColumn);
        requiredColumns = Set.copyOf(columns);
        keyDecoder = Objects.requireNonNull(keyDecoder, "keyDecoder");
        rowDecoder = Objects.requireNonNull(rowDecoder, "rowDecoder");
        validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * 创建使用无操作表级校验器的配置表定义。
     *
     * @param tableName 稳定表名；不可为空白。
     * @param source CSV 文件；不可为空。
     * @param keyColumn key 列名；不可为空白。
     * @param requiredColumns 必需列；不可为空。
     * @param keyDecoder key decoder；不可为空。
     * @param rowDecoder row decoder；不可为空。
     * @param <K> key 类型。
     * @param <V> 配置对象类型。
     * @return 不可变配置表定义；不可为空；线程安全。
     */
    public static <K, V> ConfigTableDefinition<K, V> of(
            final String tableName,
            final Path source,
            final String keyColumn,
            final Set<String> requiredColumns,
            final ConfigValueDecoder<K> keyDecoder,
            final CsvRowDecoder<V> rowDecoder) {
        return new ConfigTableDefinition<>(
                tableName,
                source,
                keyColumn,
                requiredColumns,
                keyDecoder,
                rowDecoder,
                ConfigTableValidator.noOp());
    }

    private static String requireText(final String value, final String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_DEFINITION,
                    name + " must not be blank",
                    null);
        }
        return checked;
    }
}
