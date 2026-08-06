package group.zn.zero.monitor;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 指标运行时固定安全契约。
 *
 * <p>该类型只执行有界字符遍历，不在样本热路径编译正则、创建线程或执行外部 IO。
 *
 * @author zn
 */
final class MetricContract {

    /**
     * 指标名称最大长度。
     */
    private static final int MAX_METRIC_NAME_LENGTH = 200;

    /**
     * 标签名称最大长度。
     */
    private static final int MAX_LABEL_NAME_LENGTH = 64;

    /**
     * 标签值最大长度。
     */
    private static final int MAX_LABEL_VALUE_LENGTH = 256;

    /**
     * 单项指标最大标签数量。
     */
    private static final int MAX_LABEL_COUNT = 8;

    /**
     * 忽略大小写与下划线后的全局禁止标签名。
     */
    private static final String[] FORBIDDEN_LABEL_NAMES = {
        "traceid",
        "spanid",
        "playerid",
        "accountid",
        "connectionid",
        "requestid",
        "roomid",
        "sceneid",
        "targetid",
        "operatorid",
        "approvalid",
        "token",
        "ip",
        "clientip",
        "operatorip",
        "remoteaddress"
    };

    private MetricContract() {
    }

    /**
     * 校验定义指标名称。
     *
     * @param name 指标名称。
     * @return 原名称；不可为空。
     */
    static String definitionName(final String name) {
        return requireMetricName(name, MonitorErrorCode.METRIC_DEFINITION_INVALID);
    }

    /**
     * 校验样本指标名称。
     *
     * @param name 指标名称。
     * @return 原名称；不可为空。
     */
    static String sampleName(final String name) {
        return requireMetricName(name, MonitorErrorCode.METRIC_SAMPLE_INVALID);
    }

    /**
     * 校验定义文本。
     *
     * @param value 文本值。
     * @param field 字段名；仅用于受控错误摘要。
     * @return 原文本；不可为空。
     */
    static String definitionText(final String value, final String field) {
        if (value == null || value.isBlank() || containsControl(value)) {
            throw failure(MonitorErrorCode.METRIC_DEFINITION_INVALID, field + " is invalid", null);
        }
        return value;
    }

    /**
     * 校验并复制有序标签 schema。
     *
     * @param labelNames 标签名称列表。
     * @return 不可变、有序、无重复的标签名称列表；不可为空。
     */
    static List<String> definitionLabels(final List<String> labelNames) {
        if (labelNames == null || labelNames.size() > MAX_LABEL_COUNT) {
            throw failure(
                    MonitorErrorCode.METRIC_DEFINITION_INVALID,
                    "label schema size is invalid",
                    null);
        }
        List<String> copy;
        try {
            copy = List.copyOf(labelNames);
        } catch (NullPointerException exception) {
            throw failure(
                    MonitorErrorCode.METRIC_DEFINITION_INVALID,
                    "label schema contains null",
                    exception);
        }
        for (int index = 0; index < copy.size(); index++) {
            String labelName = copy.get(index);
            requireLabelName(labelName, MonitorErrorCode.METRIC_DEFINITION_INVALID);
            if (isForbiddenLabelName(labelName)) {
                throw failure(MonitorErrorCode.METRIC_LABEL_FORBIDDEN, "label name is forbidden", null);
            }
            for (int previous = 0; previous < index; previous++) {
                if (labelName.equals(copy.get(previous))) {
                    throw failure(
                            MonitorErrorCode.METRIC_DEFINITION_INVALID,
                            "label schema contains duplicate name",
                            null);
                }
            }
        }
        return copy;
    }

    /**
     * 校验并复制样本标签。
     *
     * @param labels 样本标签。
     * @return 不可变标签 Map；不可为空；调用方不得依赖其迭代顺序。
     */
    static Map<String, String> sampleLabels(final Map<String, String> labels) {
        if (labels == null || labels.size() > MAX_LABEL_COUNT) {
            throw failure(MonitorErrorCode.METRIC_SAMPLE_INVALID, "label count is invalid", null);
        }
        Map<String, String> copy;
        try {
            copy = Map.copyOf(labels);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw failure(MonitorErrorCode.METRIC_SAMPLE_INVALID, "label map is invalid", exception);
        }
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            String labelName = entry.getKey();
            String labelValue = entry.getValue();
            requireLabelName(labelName, MonitorErrorCode.METRIC_SAMPLE_INVALID);
            if (isForbiddenLabelName(labelName)) {
                throw failure(MonitorErrorCode.METRIC_LABEL_FORBIDDEN, "label name is forbidden", null);
            }
            requireLabelValue(labelValue);
            if (isIpLiteral(labelValue)) {
                throw failure(MonitorErrorCode.METRIC_LABEL_FORBIDDEN, "IP label value is forbidden", null);
            }
        }
        return copy;
    }

    /**
     * 校验样本时间。
     *
     * @param time 样本时间。
     * @return 原时间；不可为空。
     */
    static Instant sampleTime(final Instant time) {
        if (time == null) {
            throw failure(MonitorErrorCode.METRIC_SAMPLE_INVALID, "sample time is invalid", null);
        }
        return time;
    }

    /**
     * 校验样本标签与定义 schema 完全一致。
     *
     * @param definition 指标定义；不可为空。
     * @param sample 指标样本；不可为空。
     */
    static void requireMatchingSchema(final MetricDefinition definition, final MetricSample sample) {
        List<String> labelNames = definition.labelNames();
        Map<String, String> labels = sample.labels();
        if (labelNames.size() != labels.size()) {
            throw schemaMismatch();
        }
        for (String labelName : labelNames) {
            if (!labels.containsKey(labelName)) {
                throw schemaMismatch();
            }
        }
    }

    /**
     * 创建带受控摘要的模块异常。
     *
     * @param errorCode 监控错误码；不可为空。
     * @param detail 受控错误摘要；不可为空。
     * @param cause 原始异常；可为空。
     * @return 统一异常；不可为空。
     */
    static ZeroException failure(
            final MonitorErrorCode errorCode,
            final String detail,
            final Throwable cause) {
        return ZeroException.of(errorCode, errorCode.message() + ": " + detail, cause);
    }

    private static String requireMetricName(final String value, final MonitorErrorCode errorCode) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_METRIC_NAME_LENGTH
                || !isIdentifierStart(value.charAt(0))) {
            throw failure(errorCode, "metric name is invalid", null);
        }
        for (int index = 1; index < value.length(); index++) {
            if (!isIdentifierPart(value.charAt(index))) {
                throw failure(errorCode, "metric name is invalid", null);
            }
        }
        return value;
    }

    private static void requireLabelName(final String value, final MonitorErrorCode errorCode) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_LABEL_NAME_LENGTH
                || !isIdentifierStart(value.charAt(0))) {
            throw failure(errorCode, "label name is invalid", null);
        }
        for (int index = 1; index < value.length(); index++) {
            if (!isIdentifierPart(value.charAt(index))) {
                throw failure(errorCode, "label name is invalid", null);
            }
        }
    }

    private static void requireLabelValue(final String value) {
        if (value == null || value.length() > MAX_LABEL_VALUE_LENGTH || containsControl(value)) {
            throw failure(MonitorErrorCode.METRIC_SAMPLE_INVALID, "label value is invalid", null);
        }
    }

    private static boolean isIdentifierStart(final char value) {
        return value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean isIdentifierPart(final char value) {
        return isIdentifierStart(value) || value >= '0' && value <= '9';
    }

    private static boolean containsControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForbiddenLabelName(final String labelName) {
        for (String forbidden : FORBIDDEN_LABEL_NAMES) {
            if (canonicalEquals(labelName, forbidden)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canonicalEquals(final String labelName, final String canonical) {
        int canonicalIndex = 0;
        for (int index = 0; index < labelName.length(); index++) {
            char current = labelName.charAt(index);
            if (current == '_') {
                continue;
            }
            if (canonicalIndex >= canonical.length()
                    || asciiLower(current) != canonical.charAt(canonicalIndex)) {
                return false;
            }
            canonicalIndex++;
        }
        return canonicalIndex == canonical.length();
    }

    private static char asciiLower(final char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    private static boolean isIpLiteral(final String value) {
        return isIpv4Literal(value, 0, value.length()) || isIpv6Literal(value);
    }

    private static boolean isIpv4Literal(final String value, final int start, final int end) {
        long first = -1L;
        long second = -1L;
        long third = -1L;
        long fourth = -1L;
        int components = 0;
        int index = start;
        while (index < end) {
            int digits = 0;
            long number = 0L;
            while (index < end && value.charAt(index) != '.') {
                char current = value.charAt(index);
                if (current < '0' || current > '9') {
                    return false;
                }
                int digit = current - '0';
                if (number > (0xFFFF_FFFFL - digit) / 10L) {
                    return false;
                }
                number = number * 10L + digit;
                digits++;
                index++;
            }
            if (digits == 0 || components == 4) {
                return false;
            }
            switch (components++) {
                case 0 -> first = number;
                case 1 -> second = number;
                case 2 -> third = number;
                case 3 -> fourth = number;
                default -> throw new IllegalStateException("unreachable IPv4 component");
            }
            if (index < end) {
                index++;
                if (index == end) {
                    return false;
                }
            }
        }
        return switch (components) {
            case 1 -> first <= 0xFFFF_FFFFL;
            case 2 -> first <= 0xFFL && second <= 0xFF_FFFFL;
            case 3 -> first <= 0xFFL && second <= 0xFFL && third <= 0xFFFFL;
            case 4 -> first <= 0xFFL && second <= 0xFFL && third <= 0xFFL && fourth <= 0xFFL;
            default -> false;
        };
    }

    private static boolean isIpv6Literal(final String value) {
        int start = 0;
        int end = value.length();
        if (end >= 2 && value.charAt(0) == '[' && value.charAt(end - 1) == ']') {
            start++;
            end--;
        } else if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            return false;
        }
        int zone = indexOf(value, '%', start, end);
        if (zone >= 0) {
            if (zone == start || zone == end - 1) {
                return false;
            }
            end = zone;
        }
        if (indexOf(value, ':', start, end) < 0) {
            return false;
        }
        int compression = indexOfDoubleColon(value, start, end);
        if (compression < 0) {
            return countIpv6Groups(value, start, end) == 8;
        }
        if (indexOfDoubleColon(value, compression + 2, end) >= 0) {
            return false;
        }
        int leftGroups = countIpv6Groups(value, start, compression);
        int rightGroups = countIpv6Groups(value, compression + 2, end);
        return leftGroups >= 0 && rightGroups >= 0 && leftGroups + rightGroups < 8;
    }

    private static int countIpv6Groups(final String value, final int start, final int end) {
        if (start == end) {
            return 0;
        }
        int groups = 0;
        int segmentStart = start;
        while (segmentStart < end) {
            int segmentEnd = indexOf(value, ':', segmentStart, end);
            if (segmentEnd < 0) {
                segmentEnd = end;
            }
            if (segmentEnd == segmentStart) {
                return -1;
            }
            boolean ipv4 = indexOf(value, '.', segmentStart, segmentEnd) >= 0;
            if (ipv4) {
                if (segmentEnd != end || !isIpv4Literal(value, segmentStart, segmentEnd)) {
                    return -1;
                }
                groups += 2;
            } else if (!isHexGroup(value, segmentStart, segmentEnd)) {
                return -1;
            } else {
                groups++;
            }
            if (groups > 8 || segmentEnd == end) {
                return groups > 8 ? -1 : groups;
            }
            segmentStart = segmentEnd + 1;
            if (segmentStart == end) {
                return -1;
            }
        }
        return groups;
    }

    private static boolean isHexGroup(final String value, final int start, final int end) {
        int length = end - start;
        if (length < 1 || length > 4) {
            return false;
        }
        for (int index = start; index < end; index++) {
            char current = value.charAt(index);
            boolean hex = current >= '0' && current <= '9'
                    || current >= 'A' && current <= 'F'
                    || current >= 'a' && current <= 'f';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfDoubleColon(final String value, final int start, final int end) {
        for (int index = start; index + 1 < end; index++) {
            if (value.charAt(index) == ':' && value.charAt(index + 1) == ':') {
                return index;
            }
        }
        return -1;
    }

    private static int indexOf(final String value, final char target, final int start, final int end) {
        for (int index = start; index < end; index++) {
            if (value.charAt(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private static ZeroException schemaMismatch() {
        return failure(
                MonitorErrorCode.METRIC_LABEL_SCHEMA_MISMATCH,
                "sample labels must exactly match definition schema",
                null);
    }
}
