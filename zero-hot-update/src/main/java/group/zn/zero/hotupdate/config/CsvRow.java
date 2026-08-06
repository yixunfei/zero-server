package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ZeroException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 CSV 数据行。
 *
 * <p>列值按 CSV 表头顺序保存。该对象只承载当前行文本，不保存文件完整路径，
 * 也不允许调用方修改内部列集合。
 *
 * @author zn
 */
public final class CsvRow {

    /**
     * CSV 物理记录号。
     */
    private final long recordNumber;

    /**
     * 按表头顺序保存的列值。
     */
    private final Map<String, String> values;

    /**
     * 创建 CSV 数据行。
     *
     * @param recordNumber CSV 物理记录号；必须大于 0。
     * @param values 列值；不可为空；调用后会复制并保持迭代顺序。
     * @throws IllegalArgumentException 当记录号非法时抛出。
     * @throws NullPointerException 当列集合、列名或列值为空时抛出。
     */
    public CsvRow(final long recordNumber, final Map<String, String> values) {
        if (recordNumber < 1) {
            throw new IllegalArgumentException("recordNumber must be positive");
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((name, value) -> copied.put(
                Objects.requireNonNull(name, "columnName"),
                Objects.requireNonNull(value, "columnValue")));
        this.recordNumber = recordNumber;
        this.values = Collections.unmodifiableMap(copied);
    }

    /**
     * 返回 CSV 物理记录号。
     *
     * @return 记录号；大于 0；线程安全。
     */
    public long recordNumber() {
        return recordNumber;
    }

    /**
     * 按列名查找原始文本。
     *
     * @param columnName 列名；不可为空。
     * @return 列值；为空表示表头不包含该列；线程安全。
     * @throws NullPointerException 当列名为空时抛出。
     */
    public Optional<String> value(final String columnName) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(columnName, "columnName")));
    }

    /**
     * 读取必需列原始文本。
     *
     * @param columnName 列名；不可为空。
     * @return 列值；不可为空；线程安全。
     * @throws ZeroException 当表头不包含该列时抛出并绑定配置热重载 ErrorCode。
     */
    public String require(final String columnName) {
        return value(columnName).orElseThrow(() -> ZeroException.of(
                ConfigReloadErrorCode.REQUIRED_COLUMN_MISSING,
                "required config column is missing: " + columnName,
                null));
    }

    /**
     * 返回当前行列值快照。
     *
     * @return 不可变、有序、非空或空、线程安全的列值映射。
     */
    public Map<String, String> values() {
        return values;
    }
}
