package group.zn.zero.player;

/** 玩家资料不存在时的显式装配策略。 @author zn */
public enum MissingPlayerPolicy {
    /** 拒绝未知 UID，要求账号或业务流程先完成建档。 */
    REJECT,
    /** 为本地原型创建最小默认档案；生产账号系统应谨慎启用。 */
    CREATE_DEFAULT
}
