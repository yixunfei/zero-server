package group.zn.zero.log;

/**
 * 日志处理器。
 *
 * <p>处理器用于追加字段或转换日志记录。处理器必须保持轻量，
 * 不应在事件或 Actor 热路径中执行不可控远程 IO。
 *
 * @author zn
 */
@FunctionalInterface
public interface LogProcessor {

    /**
     * 处理日志记录。
     *
     * @param record 原始日志记录；不可为空。
     * @return 处理后的日志记录；不可为空。
     * @throws RuntimeException 处理失败时抛出；标准管线会包装为
     *         {@link LogErrorCode#PROCESSOR_FAILED} 并保留 cause。
     */
    ZeroLogRecord process(ZeroLogRecord record);
}
