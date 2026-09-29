package group.zn.zero.net.kcp;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * 系统码 FEC 管线；数据片不等待组满，尾组以零 shard 补齐并发送实际片数。
 * 元数据和所有 shard 必须先经过外层保护；本类单 EventLoop 独占，不创建线程。
 * @author zn
 */
final class KcpFecCodec implements AutoCloseable {
    /** id(2)/group(8)/index(1)/data(1)/parity(1)/actual(1)/length(2)。 */
    static final int HEADER_BYTES = 16;
    /** 会话配置。 */
    private final KcpFecOptions options;
    /** 独占算法。 */
    private final KcpFecStrategy strategy;
    /** shard 字节数，包含大端有效长度。 */
    private final int width;
    /** 每组保守字节预算。 */
    private final long groupCharge;
    /** 服务器全局预留。 */
    private final LongPredicate reserve;
    /** 服务器全局释放。 */
    private final LongConsumer release;
    /** 固定原因指标。 */
    private final Consumer<String> metric;
    /** 当前缓存字节。 */
    private long bytes;
    /** 发送矩阵，满组后立即释放。 */
    private byte[][] sending;
    /** 已发送数据片数。 */
    private int count;
    /** 当前发送组 ID，正数且不回绕。 */
    private long groupId = 1;
    /** 尾组发送截止时间。 */
    private long flushAt = Long.MAX_VALUE;
    /** 接收组，包含已完成组的有界去重墓碑。 */
    private final Map<Long, Group> receiving = new LinkedHashMap<>();
    /** 最高接收组。 */
    private long highest;

    KcpFecCodec(final KcpFecOptions options, final KcpFecStrategy strategy, final int mtu,
            final LongPredicate reserve, final LongConsumer release, final Consumer<String> metric) {
        this.options = options; this.strategy = strategy; width = mtu + 2;
        groupCharge = (long) (options.dataShards() + options.parityShards()) * (width + 32L) + 256;
        this.reserve = reserve; this.release = release; this.metric = metric;
    }
    void send(final byte[] body, final long now, final Consumer<byte[]> emit) {
        if (body.length > width - 2) throw new IllegalArgumentException("FEC body exceeds MTU");
        if (sending == null) {
            if (!charge()) throw new IllegalStateException("FEC sending budget exhausted");
            sending = matrix(); flushAt = now + options.flushDelay().toNanos();
        }
        ByteBuffer.wrap(sending[count]).putShort((short) body.length).put(body);
        emit.accept(packet(groupId, count, 0, sending[count]));
        count++;
        if (count == options.dataShards()) flush(emit);
    }
    void tick(final long now, final Consumer<byte[]> emit) {
        expire(now);
        if (sending != null && now >= flushAt) flush(emit);
    }
    long deadline() { return flushAt; }
    private void flush(final Consumer<byte[]> emit) {
        try {
            strategy.encode(sending);
            for (int i = options.dataShards(); i < sending.length; i++) {
                emit.accept(packet(groupId, i, count, sending[i])); metric.accept("fec.parity_sent");
            }
        } finally {
            erase(sending); sending = null; count = 0; flushAt = Long.MAX_VALUE; uncharge();
        }
        if (groupId == Long.MAX_VALUE) throw new IllegalStateException("FEC group exhausted");
        groupId++;
    }
    private byte[] packet(final long id, final int index, final int actual, final byte[] shard) {
        return ByteBuffer.allocate(HEADER_BYTES + width).putShort((short) options.wireId()).putLong(id)
                .put((byte) index).put((byte) options.dataShards()).put((byte) options.parityShards())
                .put((byte) actual).putShort((short) width).put(shard).array();
    }
    void input(final byte[] packet, final long now, final Consumer<byte[]> deliver) {
        expire(now);
        if (packet.length != HEADER_BYTES + width) { metric.accept("fec.invalid"); return; }
        ByteBuffer in = ByteBuffer.wrap(packet);
        int algorithm = Short.toUnsignedInt(in.getShort()); long id = in.getLong();
        int index = Byte.toUnsignedInt(in.get()), data = Byte.toUnsignedInt(in.get());
        int parity = Byte.toUnsignedInt(in.get()), actual = Byte.toUnsignedInt(in.get());
        int length = Short.toUnsignedInt(in.getShort());
        if (algorithm != options.wireId() || data != options.dataShards() || parity != options.parityShards()
                || length != width || id <= 0 || index >= data + parity || actual > data
                || index < data && actual != 0 || index >= data && actual == 0) { metric.accept("fec.invalid"); return; }
        if (id <= highest - options.maxGroups()) { metric.accept("fec.expired"); return; }
        highest = Math.max(highest, id);
        Group group = receiving.get(id);
        if (group == null) {
            if (receiving.size() >= options.maxGroups()) evictFirst();
            if (!charge()) { metric.accept("fec.budget"); return; }
            group = new Group(matrix(), now + options.expiry().toNanos()); receiving.put(id, group);
        }
        if (group.shards == null || group.present[index]) return;
        if (actual > 0) {
            if (group.actual != 0 && group.actual != actual) { metric.accept("fec.invalid"); return; }
            for (int i = actual; i < data; i++) {
                if (group.delivered[i]) { metric.accept("fec.invalid"); return; }
                group.present[i] = true;
            }
            group.actual = actual;
        } else if (group.actual > 0 && index >= group.actual) { metric.accept("fec.invalid"); return; }
        in.get(group.shards[index]); group.present[index] = true;
        if (index < data) deliver(group, index, deliver, false);
        int available = 0;
        for (boolean present : group.present) if (present) available++;
        if (available >= data) {
            if (!strategy.recover(group.shards, group.present)) { metric.accept("fec.failed"); return; }
            int effective = group.actual == 0 ? data : group.actual;
            for (int i = 0; i < effective; i++) if (!group.delivered[i]) deliver(group, i, deliver, true);
            erase(group.shards); group.shards = null; uncharge();
        }
    }
    private void deliver(final Group group, final int index, final Consumer<byte[]> deliver, final boolean recovered) {
        byte[] shard = group.shards[index];
        int length = Short.toUnsignedInt(ByteBuffer.wrap(shard).getShort());
        if (length == 0 || length > width - 2) { metric.accept("fec.invalid"); return; }
        group.delivered[index] = true;
        if (recovered) metric.accept("fec.recovered");
        deliver.accept(Arrays.copyOfRange(shard, 2, length + 2));
    }
    private void expire(final long now) {
        var iterator = receiving.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); Group group = entry.getValue();
            if (now >= group.expires || entry.getKey() <= highest - options.maxGroups()) {
                if (group.shards != null) { erase(group.shards); uncharge(); metric.accept("fec.expired"); }
                iterator.remove();
            }
        }
    }
    private void evictFirst() {
        var iterator = receiving.values().iterator(); Group group = iterator.next(); iterator.remove();
        if (group.shards != null) { erase(group.shards); uncharge(); metric.accept("fec.budget"); }
    }
    private boolean charge() {
        if (bytes > options.maxBytes() - groupCharge || !reserve.test(groupCharge)) return false;
        bytes += groupCharge; return true;
    }
    private void uncharge() { bytes -= groupCharge; release.accept(groupCharge); }
    private byte[][] matrix() { return new byte[options.dataShards() + options.parityShards()][width]; }
    private static void erase(final byte[][] matrix) { for (byte[] shard : matrix) Arrays.fill(shard, (byte) 0); }
    long pendingBytes() { return bytes; }
    boolean sending() { return sending != null; }
    /** 释放全部矩阵和预算；幂等，单执行域。 */
    @Override public void close() {
        if (sending != null) { erase(sending); sending = null; uncharge(); }
        for (Group group : receiving.values()) if (group.shards != null) { erase(group.shards); uncharge(); }
        receiving.clear(); flushAt = Long.MAX_VALUE;
    }
    /** 接收组与完成墓碑。 @author zn */
    private static final class Group {
        /** 在完成或过期后擦除的矩阵。 */
        private byte[][] shards;
        /** 已接收和补零位图。 */
        private final boolean[] present;
        /** 已交给 KCP 的位图。 */
        private final boolean[] delivered;
        /** 缓存期限。 */
        private final long expires;
        /** 尾组实际数据片数，0 表示未知。 */
        private int actual;
        Group(final byte[][] shards, final long expires) {
            this.shards = shards; this.expires = expires;
            present = new boolean[shards.length]; delivered = new boolean[shards.length];
        }
    }
}
