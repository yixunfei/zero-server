package group.zn.zero.log;

/**
 * 日志终端装配 SPI。
 *
 * <p>该接口不执行结构或敏感字段校验，业务、observer 和 GM 不得直接依赖；它只应由装配层作为
 * {@link LogPipeline} 的最终下游。实现类可以写入内存、文件、Kafka 或其他下游。本接口不规定
 * 异步模型，生产环境接入热路径前需要单独评估背压、批量、失败重试和降级策略。
 *
 * @author zn
 */
public interface LogSink {

    /**
     * 写入已经通过终端安全门的日志记录。
     *
     * @param record 日志记录；不可为空。
     * @throws RuntimeException 写入失败时抛出；标准 {@link LogPipeline} 会包装为
     *         {@link LogErrorCode#SINK_FAILED} 并保留 cause。
     */
    void append(ZeroLogRecord record);
}
