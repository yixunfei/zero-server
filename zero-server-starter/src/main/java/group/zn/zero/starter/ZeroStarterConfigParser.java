package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Locale;
import java.util.Objects;

/**
 * Starter 显式能力工厂共用的严格配置解析器。
 *
 * <p>该类型只读取 {@link ZeroConfig} 并执行格式校验，不缓存配置、不启动组件、不修改业务数据。
 * 所有方法均为无状态、线程安全方法；调用方通过 ErrorCode 保留所属能力的错误分类。</p>
 *
 * @author zn
 */
final class ZeroStarterConfigParser {

    private ZeroStarterConfigParser() {
    }

    /**
     * 读取严格布尔值。
     *
     * @param config 统一配置；不可为空。
     * @param key 配置键；不可为空白。
     * @param defaultValue 默认值。
     * @param errorCode 非法值绑定的错误码；不可为空。
     * @return 解析结果；线程安全。
     * @throws ZeroException 当值不是忽略大小写的 true 或 false 时抛出。
     */
    static boolean strictBoolean(
            final ZeroConfig config,
            final String key,
            final boolean defaultValue,
            final ErrorCode errorCode) {
        String value = value(config, key, Boolean.toString(defaultValue)).toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw invalid(errorCode, key + " must be true or false", null);
    }

    /**
     * 读取正整数。
     *
     * @param config 统一配置；不可为空。
     * @param key 配置键；不可为空白。
     * @param defaultValue 正数默认值。
     * @param errorCode 非法值绑定的错误码；不可为空。
     * @return 正整数配置值；线程安全。
     * @throws ZeroException 当值不是 int 范围内正整数时抛出。
     */
    static int positiveInt(
            final ZeroConfig config,
            final String key,
            final int defaultValue,
            final ErrorCode errorCode) {
        String current = value(config, key, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(current);
            if (parsed <= 0) {
                throw invalid(errorCode, key + " must be positive", null);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(errorCode, key + " must be a positive integer", ex);
        }
    }

    /**
     * 读取正长整数。
     *
     * @param config 统一配置；不可为空。
     * @param key 配置键；不可为空白。
     * @param defaultValue 正数默认值。
     * @param errorCode 非法值绑定的错误码；不可为空。
     * @return 正长整数配置值；线程安全。
     * @throws ZeroException 当值不是 long 范围内正整数时抛出。
     */
    static long positiveLong(
            final ZeroConfig config,
            final String key,
            final long defaultValue,
            final ErrorCode errorCode) {
        String current = value(config, key, Long.toString(defaultValue));
        try {
            long parsed = Long.parseLong(current);
            if (parsed <= 0L) {
                throw invalid(errorCode, key + " must be positive", null);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(errorCode, key + " must be a positive integer", ex);
        }
    }

    /**
     * 读取去除首尾空白后的非空文本。
     *
     * @param config 统一配置；不可为空。
     * @param key 配置键；不可为空白。
     * @param defaultValue 默认文本；不可为空。
     * @param errorCode 非法值绑定的错误码；不可为空。
     * @return 非空白配置值；不可为空；线程安全。
     * @throws ZeroException 当结果为空白时抛出。
     */
    static String nonBlank(
            final ZeroConfig config,
            final String key,
            final String defaultValue,
            final ErrorCode errorCode) {
        String current = value(config, key, defaultValue);
        if (current.isEmpty()) {
            throw invalid(errorCode, key + " must not be blank", null);
        }
        return current;
    }

    private static String value(final ZeroConfig config, final String key, final String defaultValue) {
        ZeroConfig checkedConfig = Objects.requireNonNull(config, "config");
        String checkedKey = Objects.requireNonNull(key, "key");
        return checkedConfig.getOrDefault(checkedKey, Objects.requireNonNull(defaultValue, "defaultValue")).trim();
    }

    private static ZeroException invalid(
            final ErrorCode errorCode,
            final String message,
            final Throwable cause) {
        return ZeroException.of(Objects.requireNonNull(errorCode, "errorCode"), message, cause);
    }
}
