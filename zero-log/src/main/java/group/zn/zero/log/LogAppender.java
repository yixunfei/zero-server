package group.zn.zero.log;

/**
 * 经过框架安全边界的业务日志写入端口。
 *
 * <p>业务模块、observer、GM、示例和脚手架只应依赖该接口。默认实现 {@link LogPipeline}
 * 会执行结构校验、敏感字段清洗、用户处理器和不可关闭的终端复验。
 *
 * @author zn
 */
@FunctionalInterface
public interface LogAppender {

    /**
     * 安全写入日志记录。
     *
     * <p>该方法同步执行且不修改传入记录；线程安全性由具体实现声明。调用方不得在 Actor 或
     * Netty IO 热路径中接入包含不可控远程 IO 的实现。
     *
     * @param record 日志记录；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 记录非法、敏感内容被拒绝、处理器失败或
     *         终端写入失败时抛出；异常始终绑定 {@link LogErrorCode}。
     */
    void append(ZeroLogRecord record);
}
