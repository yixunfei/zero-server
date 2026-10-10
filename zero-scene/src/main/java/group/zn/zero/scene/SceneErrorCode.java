package group.zn.zero.scene;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 本地场景实体领域错误。 @author zn */
public enum SceneErrorCode implements ErrorCode {
    /** 移动目标尚未进入该场景。 */
    NOT_ENTERED;

    /** @return 请求错误分类；线程安全。 */
    @Override public ErrorCategory category() { return ErrorCategory.CLIENT_REQUEST; }
    /** @return 稳定错误码；线程安全。 */
    @Override public String code() { return "ZERO-SCENE-NOT-ENTERED"; }
    /** @return 安全说明；线程安全。 */
    @Override public String message() { return "scene entity has not entered"; }
}
