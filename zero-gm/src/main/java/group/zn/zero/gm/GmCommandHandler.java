package group.zn.zero.gm;

/**
 * GM 指令处理器。
 *
 * @author zn
 */
public interface GmCommandHandler {

    /**
     * 执行 dry-run 预演逻辑。
     *
     * <p>本方法由调用方线程同步调用。实现方不得在这里直接修改玩家、场景或持久化状态；
     * 如果需要读取 Actor 绑定状态，必须经由业务侧已经确认线程安全的查询入口。
     * 实现方抛出的运行时异常会向上抛出，不会被执行器吞掉。</p>
     *
     * @param request GM 指令执行请求；不可为空。
     * @return dry-run 预演结果；不可为空；是否有序取决于业务填充的 previewData。
     * @throws RuntimeException 当预演失败、参数非法或业务查询失败时可向上抛出。
     */
    GmDryRunResult dryRun(GmCommandExecutionRequest request);

    /**
     * 执行正式 GM 指令逻辑。
     *
     * <p>本方法由调用方线程同步调用。实现方如需修改玩家、场景或核心状态，必须通过 Actor
     * 消息、Repository / DataService 或业务侧确认的线程绑定入口，不得直接跨 Actor 修改状态。
     * 实现方抛出的运行时异常会向上抛出，不会被执行器吞掉。只有能够证明没有产生任何业务
     * 副作用时才允许返回 rejected 结果；成功返回表示业务已提交，抛错或非法结果视为提交状态未知。</p>
     *
     * @param request GM 指令执行请求；不可为空。
     * @return 正式执行结果；不可为空；是否有序取决于业务填充的 data。
     * @throws RuntimeException 当执行失败、参数非法、权限前置条件不满足或数据写入失败时可向上抛出。
     */
    GmCommandExecutionResult execute(GmCommandExecutionRequest request);
}
