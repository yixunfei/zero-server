package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ZeroException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Apache Commons CSV 配置表候选加载器。
 *
 * <p>该类只负责文件读取、RFC 4180 解析、typed decode 和候选校验，不发布运行时快照。
 *
 * @author zn
 */
final class CsvConfigTableLoader {

    /**
     * RFC 4180 严格表头格式。
     */
    private static final CSVFormat CSV_FORMAT = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .get();

    private CsvConfigTableLoader() {
    }

    /**
     * 加载完整候选快照。
     *
     * @param definition 配置表定义；不可为空。
     * @param version 候选版本；大于 0。
     * @param loadedAt 加载时间；不可为空。
     * @param <K> 配置 key 类型。
     * @param <V> 配置对象类型。
     * @return 完整候选快照；不可为空，尚未发布。
     * @throws ZeroException 当文件、CSV、decode 或校验失败时抛出并绑定 ErrorCode。
     */
    static <K, V> ConfigTableSnapshot<K, V> load(
            final ConfigTableDefinition<K, V> definition,
            final long version,
            final Instant loadedAt) {
        ConfigTableDefinition<K, V> checkedDefinition = Objects.requireNonNull(definition, "definition");
        byte[] content = readContent(checkedDefinition);
        String checksum = sha256(content);
        Map<K, V> values = parse(checkedDefinition, content);
        validate(checkedDefinition, values);
        return new ConfigTableSnapshot<>(
                checkedDefinition.tableName(),
                version,
                checksum,
                Objects.requireNonNull(loadedAt, "loadedAt"),
                values);
    }

    private static <K, V> byte[] readContent(final ConfigTableDefinition<K, V> definition) {
        try {
            return Files.readAllBytes(definition.source());
        } catch (IOException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.FILE_READ_FAILED,
                    "failed to read config table: " + safeSourceName(definition),
                    ex);
        }
    }

    private static String sha256(final byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.CSV_PARSE_FAILED,
                    "SHA-256 is not available",
                    ex);
        }
    }

    private static <K, V> Map<K, V> parse(
            final ConfigTableDefinition<K, V> definition,
            final byte[] content) {
        try (Reader reader = utf8Reader(content); CSVParser parser = CSV_FORMAT.parse(reader)) {
            List<String> headerNames = parser.getHeaderNames();
            requireColumns(definition, headerNames);
            LinkedHashMap<K, V> values = new LinkedHashMap<>();
            for (CSVRecord record : parser) {
                decodeRecord(definition, headerNames, record, values);
            }
            return Collections.unmodifiableMap(values);
        } catch (ZeroException ex) {
            throw ex;
        } catch (UncheckedIOException ex) {
            throw csvFailure(definition, ex.getCause());
        } catch (IOException | IllegalArgumentException ex) {
            throw csvFailure(definition, ex);
        }
    }

    private static Reader utf8Reader(final byte[] content) throws IOException {
        PushbackReader reader = new PushbackReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8),
                1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
        return reader;
    }

    private static <K, V> void requireColumns(
            final ConfigTableDefinition<K, V> definition,
            final List<String> headerNames) {
        for (String column : definition.requiredColumns()) {
            if (!headerNames.contains(column)) {
                throw ZeroException.of(
                        ConfigReloadErrorCode.REQUIRED_COLUMN_MISSING,
                        "required column is missing: " + column,
                        null);
            }
        }
    }

    private static <K, V> void decodeRecord(
            final ConfigTableDefinition<K, V> definition,
            final List<String> headerNames,
            final CSVRecord record,
            final Map<K, V> values) {
        if (!record.isConsistent() || record.size() != headerNames.size()) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.CSV_PARSE_FAILED,
                    "config csv record column count is inconsistent at record " + record.getRecordNumber(),
                    null);
        }
        CsvRow row = toRow(headerNames, record);
        K key = decodeKey(definition, row);
        V value = decodeValue(definition, row);
        if (values.putIfAbsent(key, value) != null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.DUPLICATE_KEY,
                    "duplicate config key at record " + record.getRecordNumber(),
                    null);
        }
    }

    private static CsvRow toRow(final List<String> headerNames, final CSVRecord record) {
        LinkedHashMap<String, String> rowValues = new LinkedHashMap<>();
        for (String headerName : headerNames) {
            rowValues.put(headerName, record.get(headerName));
        }
        return new CsvRow(record.getRecordNumber(), rowValues);
    }

    private static <K, V> K decodeKey(
            final ConfigTableDefinition<K, V> definition,
            final CsvRow row) {
        String rawKey = row.require(definition.keyColumn());
        if (rawKey.isBlank()) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.EMPTY_KEY,
                    "config key is blank at record " + row.recordNumber(),
                    null);
        }
        try {
            return Objects.requireNonNull(definition.keyDecoder().decode(rawKey), "decodedKey");
        } catch (ZeroException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.KEY_DECODE_FAILED,
                    "config key decode failed at record " + row.recordNumber(),
                    ex);
        }
    }

    private static <K, V> V decodeValue(
            final ConfigTableDefinition<K, V> definition,
            final CsvRow row) {
        try {
            return Objects.requireNonNull(definition.rowDecoder().decode(row), "decodedRow");
        } catch (ZeroException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.ROW_DECODE_FAILED,
                    "config row decode failed at record " + row.recordNumber(),
                    ex);
        }
    }

    private static <K, V> void validate(
            final ConfigTableDefinition<K, V> definition,
            final Map<K, V> values) {
        try {
            List<ConfigValidationIssue> issues = Objects.requireNonNull(
                    definition.validator().validate(values),
                    "validationIssues");
            issues.forEach(issue -> Objects.requireNonNull(issue, "validationIssue"));
            if (!issues.isEmpty()) {
                throw ZeroException.of(
                        ConfigReloadErrorCode.VALIDATION_FAILED,
                        "config validation failed: " + issues.getFirst().code()
                                + " (issues=" + issues.size() + ")",
                        null);
            }
        } catch (ZeroException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.VALIDATION_FAILED,
                    "config table validator failed",
                    ex);
        }
    }

    private static <K, V> ZeroException csvFailure(
            final ConfigTableDefinition<K, V> definition,
            final Throwable cause) {
        return ZeroException.of(
                ConfigReloadErrorCode.CSV_PARSE_FAILED,
                "failed to parse config csv: " + safeSourceName(definition),
                cause);
    }

    private static <K, V> String safeSourceName(final ConfigTableDefinition<K, V> definition) {
        return definition.source().getFileName().toString();
    }
}
