package group.zn.zero.codegen.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 代码生成模块错误码。
 *
 * @author zn
 */
public enum CodegenErrorCode implements ErrorCode {

    /**
     * DSL 解析失败。
     */
    DSL_PARSE_FAILED("ZERO-CODEGEN-DSL-PARSE-FAILED", "protocol DSL parse failed"),

    /**
     * DSL 校验失败。
     */
    DSL_VALIDATION_FAILED("ZERO-CODEGEN-DSL-VALIDATION-FAILED", "protocol DSL validation failed"),

    /**
     * 目标语言暂不支持。
     */
    UNSUPPORTED_LANGUAGE("ZERO-CODEGEN-UNSUPPORTED-LANGUAGE", "unsupported codegen language"),

    /**
     * 代码生成失败。
     */
    GENERATION_FAILED("ZERO-CODEGEN-GENERATION-FAILED", "code generation failed"),

    /**
     * 输出文件失败。
     */
    OUTPUT_FAILED("ZERO-CODEGEN-OUTPUT-FAILED", "code generation output failed");

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    CodegenErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 协议错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return ErrorCategory.PROTOCOL;
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
