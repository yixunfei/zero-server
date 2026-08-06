package group.zn.zero.codegen.model;

/**
 * Java 代码生成物类型。
 *
 * @author zn
 */
public enum JavaArtifactKind {

    /**
     * 协议 DTO、枚举和轻量 marker。
     */
    DTO,

    /**
     * 协议 payload codec。
     */
    CODEC,

    /**
     * 协议号和协议定义注册入口。
     */
    PROTOCOL,

    /**
     * 协议事件业务接口。
     */
    BO,

    /**
     * 协议事件业务默认实现模板。
     */
    BO_IMPL,

    /**
     * 协议事件分发器。
     */
    DISPATCHER
}
