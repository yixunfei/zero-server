package group.zn.zero.net.kcp;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 线程安全、非阻塞的所有权 SPI；所有 CAS 必须线性化，异常不能转换为成功。
 * RELEASED/过期记录至少保留 24 小时代际墓碑（框架票据最长 24h），防止旧代际复活。
 * 远程实现必须使用调用方提供的非内联 IO 执行器，不能阻塞 Netty EventLoop。
 * @author zn
 */
public interface KcpSessionStore {
    /** 原子创建；活租约冲突返回 false，过期记录只接受更高代际；lease 为正数且最多 5min。 */
    CompletionStage<Boolean> acquire(int conv, String owner, long generation, Duration lease);
    /** 只续租匹配且未过期的 ACTIVE/FROZEN owner，不能复活过期租约。 */
    CompletionStage<Boolean> renew(int conv, String owner, long generation, Duration lease);
    /** 匹配当前代际时释放并保留墓碑，不删除更高代际；冲突返回 false。 */
    CompletionStage<Boolean> release(int conv, String owner, long generation);
    /** ACTIVE -> FROZEN；匹配当前未过期 owner，只允许一个冻结者。 */
    CompletionStage<Boolean> freeze(int conv, String owner, long generation);
    /** FROZEN -> ACTIVE；取消尚未提交的迁移，冲突返回 false。 */
    CompletionStage<Boolean> unfreeze(int conv, String owner, long generation);
    /** 冻结源 -> 待接管目标，原子保存快照和更高代际；过期/冲突返回 false。 */
    CompletionStage<Boolean> migrate(KcpSessionSnapshot snapshot, String targetOwner, long targetGeneration, Duration lease);
    /** PENDING -> ACTIVE；只允许指定目标代际成功一次，并开始目标租约。 */
    CompletionStage<Boolean> claim(int conv, String owner, long generation, Duration lease);
    /** @return 当前未过期 owner，不存在/已释放返回 null；只读、线程安全。 */
    CompletionStage<KcpSessionOwner> owner(int conv);
    /** @return 未过期 PENDING 的源快照，不存在返回 null；独立不可变对象，不含密钥或业务帧。 */
    CompletionStage<KcpSessionSnapshot> load(int conv);
}
