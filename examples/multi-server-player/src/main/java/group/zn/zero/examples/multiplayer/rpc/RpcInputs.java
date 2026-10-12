package group.zn.zero.examples.multiplayer.rpc;

import group.zn.zero.examples.multiplayer.MultiServerError;
import group.zn.zero.examples.multiplayer.MultiServerErrorCode;

/** 契约草图的统一字段校验，不修改数据，线程安全。 @author zn */
final class RpcInputs {
    private RpcInputs() { }

    /** 校验必填文本，保持原值不隐式 trim。 */
    static void text(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST, name + " must not be blank");
        }
    }

    /** 校验数值与对象条件。 */
    static void check(final boolean valid, final String name) {
        if (!valid) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST, "invalid " + name);
        }
    }
}
