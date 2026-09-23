package group.zn.zero.statesync;

import java.util.HashMap;
import java.util.Map;

/** 单 owner 的状态基线校验器，按场景和观察者隔离并拒绝旧消息。 @author zn */
public final class InMemoryStateSync {
    /** 已应用的版本和序号；由所属执行域串行访问。 */
    private final Map<Observer, Baseline> baselines = new HashMap<>();

    /**
     * 接收同步消息；旧消息不会改变状态，缺失基线要求重新同步。
     * @param envelope 输入信封；不可为空。
     * @return 不可变处理结果；不可为空。
     * @throws IllegalArgumentException 版本或序号为负时抛出。
     * 线程不安全，调用方必须使用同一 owner 执行域。
     */
    public Result accept(final SyncEnvelope envelope) {
        if (envelope.syncSeq() < 0 || envelope.stateVersion() < 0) {
            throw new IllegalArgumentException("sync sequence and state version must be non-negative");
        }
        Observer observer = new Observer(envelope.sceneId(), envelope.observerId());
        Baseline known = baselines.get(observer);
        if (known != null && (envelope.syncSeq() <= known.sequence() || envelope.stateVersion() < known.version())) {
            return new Result(Status.IGNORED, known.version());
        }
        if (envelope.kind() == SyncEnvelope.Kind.DELTA
                && (known == null || known.version() != envelope.baselineVersion())) {
            return new Result(Status.RESYNC_REQUIRED, known == null ? -1 : known.version());
        }
        baselines.put(observer, new Baseline(envelope.syncSeq(), envelope.stateVersion()));
        return new Result(Status.APPLIED, envelope.stateVersion());
    }
    /** 接收结果类别，IGNORED 表示重复或过期输入。 */
    public enum Status { APPLIED, RESYNC_REQUIRED, IGNORED }
    /** 不可变结果，baselineVersion 是当前实际基线。 */
    public record Result(Status status, long baselineVersion) { }
    /** 场景中的观察者身份。 */
    private record Observer(String scene, String observer) { }
    /** 最后成功应用的消息信息。 */
    private record Baseline(long sequence, long version) { }
}
