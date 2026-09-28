package group.zn.zero.ranking;

/**
 * 排行榜提交后的同步通知端口；不持有服务状态锁，不创建线程。
 * 并发调用可能使通知乱序；有序业务应串行调用或提供带顺序控制的实现。
 * @author zn
 */
public interface RankingEventSink {
    /**
     * 接收已提交状态的事件，可查询或调用服务。
     * @param event 不可变事件；不可为空。
     * @throws RuntimeException 交付失败时向调用方传播；不回滚状态、不自动重发。
     */
    void onEvent(RankingEvent event);
    /** 无观察器的默认实现。 */
    RankingEventSink NOOP = event -> { };
}
