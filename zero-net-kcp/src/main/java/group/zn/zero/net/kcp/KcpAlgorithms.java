package group.zn.zero.net.kcp;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** 不可变算法注册表；显式装配，无全局副作用。自定义 wire ID 必须由两端约定。 @author zn */
public final class KcpAlgorithms {
    /** 已注册保护算法工厂。 */
    private final Map<Integer, KcpDatagramProtection> protections;
    /** 单会话 FEC 工厂。 */
    private final Map<Integer, BiFunction<Integer, Integer, KcpFecStrategy>> fec;
    private KcpAlgorithms(final Map<Integer, KcpDatagramProtection> protections,
            final Map<Integer, BiFunction<Integer, Integer, KcpFecStrategy>> fec) {
        this.protections = Map.copyOf(protections); this.fec = Map.copyOf(fec);
    }
    /** @return 内置 NONE/HMAC/ChaCha/AES 与 XOR/RS 的不可变表；线程安全。 */
    public static KcpAlgorithms defaults() {
        return new KcpAlgorithms(Map.of(0, KcpProtectionStrategies.none(), 1, KcpProtectionStrategies.hmac(),
                2, KcpProtectionStrategies.chacha20(), 3, KcpProtectionStrategies.aesGcm()),
                Map.of(1, (data, parity) -> KcpFecStrategies.xor(data), 2, KcpFecStrategies::reedSolomon));
    }
    /** @param strategy 线程安全工厂。 @return 新表；原表不变，重复或非私有 ID 拒绝。 */
    public KcpAlgorithms withProtection(final KcpDatagramProtection strategy) {
        Objects.requireNonNull(strategy);
        if (strategy.wireId() < 256 || strategy.wireId() > 65535 || protections.containsKey(strategy.wireId())) {
            throw new IllegalArgumentException("custom protection ID must be unique and 256..65535");
        }
        var copy = new HashMap<>(protections); copy.put(strategy.wireId(), strategy);
        return new KcpAlgorithms(copy, fec);
    }
    /** @param id 私有 ID。 @param factory 独占实例工厂。 @return 新表；重复 ID 拒绝，无共享修改。 */
    public KcpAlgorithms withFec(final int id, final BiFunction<Integer, Integer, KcpFecStrategy> factory) {
        if (id < 256 || id > 65535 || fec.containsKey(id)) throw new IllegalArgumentException("invalid custom FEC ID");
        var copy = new HashMap<>(fec); copy.put(id, Objects.requireNonNull(factory));
        return new KcpAlgorithms(protections, copy);
    }
    /** @param id 线 ID。 @return 工厂；未知 ID 拒绝、不降级。线程安全。 */
    public KcpDatagramProtection protection(final int id) {
        var value = protections.get(id);
        if (value == null || value.tagBytes() < 0 || value.tagBytes() > 64
                || value.authenticated() && value.tagBytes() < 16) throw new IllegalArgumentException("unsupported protection");
        return value;
    }
    /** @param options 参数。 @return 独占实例，NONE 返回 null；未知或不匹配工厂拒绝。 */
    public KcpFecStrategy fec(final KcpFecOptions options) {
        if (options.wireId() == 0) return null;
        var factory = fec.get(options.wireId());
        if (factory == null) throw new IllegalArgumentException("unsupported FEC algorithm");
        var value = factory.apply(options.dataShards(), options.parityShards());
        if (value == null || value.wireId() != options.wireId() || value.dataShards() != options.dataShards()
                || value.parityShards() != options.parityShards()) throw new IllegalArgumentException("FEC factory mismatch");
        return value;
    }
    /** 启动前检查保护能力、MTU 和工厂；不创建线程，非法组合抛出参数异常。 */
    public void validate(final KcpOptions options) {
        var policy = options.transport(); var protection = protection(policy.protectionId());
        if (!protection.authenticated() && (!policy.allowUnauthenticated() || policy.paths().enabled())) {
            throw new IllegalArgumentException("unauthenticated protection requires opt-in and fixed paths");
        }
        int overhead = KcpDatagramCodec.HEADER_BYTES + protection.tagBytes()
                + (policy.fec().wireId() == 0 ? 0 : KcpFecCodec.HEADER_BYTES + 2);
        if (options.mtu() + overhead > policy.maxDatagramBytes()) throw new IllegalArgumentException("KCP MTU exceeds UDP budget");
        long group = (long) (policy.fec().dataShards() + policy.fec().parityShards()) * (options.mtu() + 34L) + 256;
        if (policy.fec().wireId() != 0 && group * 2 > policy.fec().maxBytes()) {
            throw new IllegalArgumentException("FEC budget must fit sending and receiving groups");
        }
        fec(policy.fec());
    }
}
