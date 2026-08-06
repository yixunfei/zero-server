package group.zn.zero.cache;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 缓存模块错误码。
 *
 * @author zn
 */
public enum CacheErrorCode implements ErrorCode {

    /**
     * 缓存读取失败。
     */
    READ_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-READ-FAILED", "cache read failed"),

    /**
     * 缓存写入失败。
     */
    WRITE_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-WRITE-FAILED", "cache write failed"),

    /**
     * 缓存加载失败。
     */
    LOADER_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-LOADER-FAILED", "cache loader failed"),

    /**
     * 缓存后端不可用。
     */
    BACKEND_UNAVAILABLE(ErrorCategory.CACHE, "ZERO-CACHE-BACKEND-UNAVAILABLE", "cache backend unavailable"),

    /**
     * 缓存失效失败。
     */
    INVALIDATE_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-INVALIDATE-FAILED", "cache invalidate failed"),

    /**
     * 缓存值序列化失败。
     */
    SERIALIZE_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-SERIALIZE-FAILED", "cache serialize failed"),

    /**
     * 缓存值反序列化失败。
     */
    DESERIALIZE_FAILED(ErrorCategory.CACHE, "ZERO-CACHE-DESERIALIZE-FAILED", "cache deserialize failed"),

    /**
     * 缓存加载被背压拒绝。
     */
    BACKPRESSURE_REJECTED(ErrorCategory.CACHE, "ZERO-CACHE-BACKPRESSURE-REJECTED", "cache backpressure rejected"),

    /**
     * 缓存过期值被拒绝。
     */
    STALE_VALUE_REJECTED(ErrorCategory.CACHE, "ZERO-CACHE-STALE-VALUE-REJECTED", "cache stale value rejected"),

    /**
     * 缓存 key 非法。
     */
    INVALID_KEY(ErrorCategory.CACHE, "ZERO-CACHE-INVALID-KEY", "cache key invalid"),

    /**
     * 缓存版本冲突。
     */
    VERSION_CONFLICT(ErrorCategory.CACHE, "ZERO-CACHE-VERSION-CONFLICT", "cache version conflict");

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    CacheErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认错误说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
