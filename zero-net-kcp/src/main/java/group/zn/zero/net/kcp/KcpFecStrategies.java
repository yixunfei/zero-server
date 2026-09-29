package group.zn.zero.net.kcp;

import com.backblaze.erasure.ReedSolomon;
import java.util.Arrays;
import java.util.Objects;

/** 内置 XOR 与 Reed-Solomon 编码器；无线程和全局会话状态。 @author zn */
public final class KcpFecStrategies {
    private KcpFecStrategies() { }
    /** @param data 数据 shard 数。 @return 单 parity XOR。 */
    public static KcpFecStrategy xor(final int data) { return new Coding(data, 1, false); }
    /** @param data 数据 shard 数。 @param parity 校验 shard 数。 @return Reed-Solomon。 */
    public static KcpFecStrategy reedSolomon(final int data, final int parity) {
        return new Coding(data, parity, true);
    }
    /** 内部纯算法实现；实例不跨会话共享。 @author zn */
    private static final class Coding implements KcpFecStrategy {
        private final int data;
        private final int parity;
        private final ReedSolomon rs;
        Coding(final int data, final int parity, final boolean reedSolomon) {
            if (data < 1 || data > 32 || parity < 1 || parity > 16 || data + parity > 48
                    || !reedSolomon && parity != 1) throw new IllegalArgumentException("invalid FEC shard counts");
            this.data = data; this.parity = parity; rs = reedSolomon ? ReedSolomon.create(data, parity) : null;
        }
        @Override public int wireId() { return rs == null ? 1 : 2; }
        @Override public int dataShards() { return data; }
        @Override public int parityShards() { return parity; }
        @Override public void encode(final byte[][] shards) {
            int length = validate(shards);
            if (rs != null) rs.encodeParity(shards, 0, length);
            else { Arrays.fill(shards[data], (byte) 0); for (int i = 0; i < data; i++) xorInto(shards[data], shards[i]); }
        }
        @Override public boolean recover(final byte[][] shards, final boolean[] present) {
            int length = validate(shards);
            if (present.length != shards.length) throw new IllegalArgumentException("invalid presence map");
            int count = 0, missing = -1;
            for (int i = 0; i < present.length; i++) { if (present[i]) count++; else missing = i; }
            if (count < data) return false;
            if (rs != null) rs.decodeMissing(shards, present, 0, length);
            else if (missing >= 0) { Arrays.fill(shards[missing], (byte) 0); for (int i = 0; i < shards.length; i++) if (i != missing) xorInto(shards[missing], shards[i]); }
            Arrays.fill(present, true); return true;
        }
        private int validate(final byte[][] shards) {
            if (shards == null || shards.length != data + parity) throw new IllegalArgumentException("invalid shard count");
            int length = Objects.requireNonNull(shards[0]).length;
            if (length < 1 || length > 65535) throw new IllegalArgumentException("invalid shard length");
            for (byte[] shard : shards) if (Objects.requireNonNull(shard).length != length) throw new IllegalArgumentException("unequal FEC shards");
            return length;
        }
        private static void xorInto(final byte[] target, final byte[] source) { for (int i = 0; i < target.length; i++) target[i] ^= source[i]; }
    }
}
