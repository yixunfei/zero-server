package group.zn.zero.gm;

/**
 * GM 审计 hook。
 *
 * @author zn
 */
public interface GmAuditHook {

    /**
     * 记录 GM 审计事件。
     *
     * <p>本方法由执行器同步调用。实现方可以写入内存、标准日志管线或受控外部审计系统，
     * 但不得吞掉落地失败；抛出的运行时异常会由执行器附加 phase 与四态业务提交语义。
     * 事件只含构造前安全归因、有序参数名和安全指纹，不含原始身份、请求值或异常 message。</p>
     *
     * @param event GM 审计事件；不可为空。
     * @throws RuntimeException 当审计落地失败、序列化失败或后端不可用时可向上抛出。
     */
    void record(GmAuditEvent event);
}
