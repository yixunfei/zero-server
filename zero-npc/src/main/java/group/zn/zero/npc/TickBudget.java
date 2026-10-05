package group.zn.zero.npc;

/**
 * 单次 NPC tick 的不可变预算；线程安全，不创建调度资源。
 * @param budgetMillis 非负毫秒预算；零表示不执行业务决策。
 * @param budgetOps 非负决策次数；零表示不执行业务决策。
 * @author zn
 */
public record TickBudget(long budgetMillis, int budgetOps) {
    /** @throws IllegalArgumentException 预算为负数。 */
    public TickBudget {
        if (budgetMillis < 0 || budgetOps < 0) throw new IllegalArgumentException("negative budget");
    }
}
