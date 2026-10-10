package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.core.error.ZeroException;

/** DSL 通用标识符词法规则，语言关键字由所选后端继续校验。 @author zn */
public final class ProtocolNames {
    private ProtocolNames() { }

    /**
     * 验证可移植标识符；无副作用、线程安全。
     * @param name 名称。
     * @throws ZeroException 非法时绑定 DSL_VALIDATION_FAILED。
     */
    public static void identifier(final String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            fail("invalid portable identifier: " + name);
        }
    }

    /**
     * 报告命名或模型冲突；线程安全，不修改数据。
     * @param message 可信诊断文本。
     * @throws ZeroException 始终抛出 DSL_VALIDATION_FAILED。
     */
    public static void fail(final String message) {
        throw ZeroException.of(CodegenErrorCode.DSL_VALIDATION_FAILED, message, null);
    }
}
