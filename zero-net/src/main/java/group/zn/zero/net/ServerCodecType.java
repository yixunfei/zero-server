package group.zn.zero.net;

/**
 * 网络层可选编解码类型。
 *
 * <p>当前核心主线是 `ZERO_BINARY`，JSON 和 Protobuf 仅作为快速原型或通用协议接入预留。
 *
 * @author zn
 */
public enum ServerCodecType {

    /**
     * zeroServer 默认二进制 frame。
     */
    ZERO_BINARY,

    /**
     * JSON 原型协议预留。
     */
    JSON,

    /**
     * Protobuf 通用协议预留。
     */
    PROTOBUF,

    /**
     * HTTP 文本或二进制响应。
     */
    HTTP,

    /**
     * 自定义协议预留。
     */
    CUSTOM
}
