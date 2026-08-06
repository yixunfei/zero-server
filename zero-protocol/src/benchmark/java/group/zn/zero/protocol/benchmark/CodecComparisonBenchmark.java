package group.zn.zero.protocol.benchmark;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import group.zn.zero.protocol.benchmark.fb.SampleAttr;
import group.zn.zero.protocol.benchmark.fb.SampleItem;
import group.zn.zero.protocol.benchmark.fb.SamplePayload;
import group.zn.zero.protocol.benchmark.pb.CodecComparisonProto;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Zero Binary Protocol、Protobuf 与 FlatBuffers 的横向轻量基准。
 *
 * <p>该类是人工 benchmark，不进入生产路径。它使用同一份逻辑 payload，
 * 分别测量编码、完整字段扫描解码、编码后解码往返和平均 payload size。
 *
 * @author zn
 */
public final class CodecComparisonBenchmark {

    /**
     * 默认样本数量。
     */
    private static final int DEFAULT_SAMPLE_COUNT = 1024;

    /**
     * 默认单轮迭代次数。
     */
    private static final int DEFAULT_ITERATIONS = 200_000;

    /**
     * 默认预热轮数。
     */
    private static final int DEFAULT_WARMUP_ROUNDS = 4;

    /**
     * 默认测量轮数。
     */
    private static final int DEFAULT_MEASURE_ROUNDS = 5;

    /**
     * 黑洞校验值，避免热点路径被完全消除。
     */
    private static volatile long blackhole;

    /**
     * 禁止实例化。
     */
    private CodecComparisonBenchmark() {
    }

    /**
     * 程序入口。
     *
     * @param args 可选参数：iterations、warmupRounds、measureRounds、sampleCount。
     * @throws Exception 当第三方 codec 编解码失败时抛出。
     */
    public static void main(final String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_ITERATIONS;
        int warmupRounds = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUP_ROUNDS;
        int measureRounds = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MEASURE_ROUNDS;
        int sampleCount = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_SAMPLE_COUNT;

        PayloadData[] samples = createSamples(sampleCount);
        CodecCase[] cases = new CodecCase[] {
            new ZeroCodecCase(),
            new ProtobufCodecCase(),
            new FlatBuffersCodecCase()
        };

        System.out.println("Codec comparison benchmark");
        System.out.println("java=" + System.getProperty("java.version"));
        System.out.println("iterations=" + iterations
                + ", warmupRounds=" + warmupRounds
                + ", measureRounds=" + measureRounds
                + ", sampleCount=" + sampleCount);
        System.out.println("payload=13 scalar/vector/table fields; nullable note on half samples");
        System.out.println();
        System.out.println("| Codec | Avg Size(bytes) | Encode(ns/op) | Full Decode(ns/op) "
                + "| Hot Decode(ns/op) | Roundtrip(ns/op) | Checksum |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");

        for (CodecCase codecCase : cases) {
            BenchmarkResult result = runCase(codecCase, samples, iterations, warmupRounds, measureRounds);
            System.out.printf("| %s | %.2f | %.2f | %.2f | %.2f | %.2f | %d |%n",
                    codecCase.name(),
                    result.averageSize(),
                    result.encodeNanos(),
                    result.decodeNanos(),
                    result.hotDecodeNanos(),
                    result.roundtripNanos(),
                    result.checksum());
        }
        System.out.println();
        System.out.println("blackhole=" + blackhole);
    }

    /**
     * 执行单个 codec 的完整基准。
     *
     * @param codecCase codec 用例；不可为空。
     * @param samples 样本数组；不可为空。
     * @param iterations 每轮迭代次数。
     * @param warmupRounds 预热轮数。
     * @param measureRounds 测量轮数。
     * @return 基准结果；不可为空。
     * @throws Exception 当编解码失败时抛出。
     */
    private static BenchmarkResult runCase(
            final CodecCase codecCase,
            final PayloadData[] samples,
            final int iterations,
            final int warmupRounds,
            final int measureRounds) throws Exception {
        byte[][] encodedSamples = encodeSamples(codecCase, samples);
        double averageSize = averageSize(encodedSamples);

        for (int round = 0; round < warmupRounds; round++) {
            blackhole ^= runEncode(codecCase, samples, iterations);
            blackhole ^= runDecode(codecCase, encodedSamples, iterations);
            blackhole ^= runHotDecode(codecCase, encodedSamples, iterations);
            blackhole ^= runRoundtrip(codecCase, samples, iterations);
        }

        Measure encode = measure(() -> runEncode(codecCase, samples, iterations), measureRounds, iterations);
        Measure decode = measure(() -> runDecode(codecCase, encodedSamples, iterations), measureRounds, iterations);
        Measure hotDecode = measure(() -> runHotDecode(codecCase, encodedSamples, iterations), measureRounds, iterations);
        Measure roundtrip = measure(() -> runRoundtrip(codecCase, samples, iterations), measureRounds, iterations);
        long checksum = encode.checksum() ^ decode.checksum() ^ hotDecode.checksum() ^ roundtrip.checksum();
        blackhole ^= checksum;
        return new BenchmarkResult(
                averageSize,
                encode.nanosPerOp(),
                decode.nanosPerOp(),
                hotDecode.nanosPerOp(),
                roundtrip.nanosPerOp(),
                checksum);
    }

    /**
     * 预编码样本。
     *
     * @param codecCase codec 用例；不可为空。
     * @param samples 样本数组；不可为空。
     * @return 编码后样本数组；不可为空。
     * @throws Exception 当编码失败时抛出。
     */
    private static byte[][] encodeSamples(final CodecCase codecCase, final PayloadData[] samples) throws Exception {
        byte[][] encoded = new byte[samples.length][];
        for (int index = 0; index < samples.length; index++) {
            encoded[index] = codecCase.encode(samples[index]);
        }
        return encoded;
    }

    /**
     * 计算平均 payload 大小。
     *
     * @param encodedSamples 编码样本；不可为空。
     * @return 平均字节数。
     */
    private static double averageSize(final byte[][] encodedSamples) {
        long total = 0L;
        for (byte[] encodedSample : encodedSamples) {
            total += encodedSample.length;
        }
        return (double) total / encodedSamples.length;
    }

    /**
     * 测量指定动作。
     *
     * @param action 待测动作；不可为空。
     * @param measureRounds 测量轮数。
     * @param iterations 每轮迭代次数。
     * @return 测量结果；不可为空。
     * @throws Exception 当动作失败时抛出。
     */
    private static Measure measure(
            final BenchmarkAction action,
            final int measureRounds,
            final int iterations) throws Exception {
        long totalNanos = 0L;
        long checksum = 0L;
        for (int round = 0; round < measureRounds; round++) {
            long start = System.nanoTime();
            checksum += action.run() + round;
            totalNanos += System.nanoTime() - start;
        }
        long operations = (long) measureRounds * iterations;
        return new Measure((double) totalNanos / operations, checksum);
    }

    /**
     * 执行编码基准。
     *
     * @param codecCase codec 用例；不可为空。
     * @param samples 样本数组；不可为空。
     * @param iterations 迭代次数。
     * @return 校验值。
     * @throws Exception 当编码失败时抛出。
     */
    private static long runEncode(
            final CodecCase codecCase,
            final PayloadData[] samples,
            final int iterations) throws Exception {
        long checksum = 0L;
        for (int index = 0; index < iterations; index++) {
            byte[] bytes = codecCase.encode(samples[index & (samples.length - 1)]);
            checksum += bytes.length;
            checksum += bytes[0] & 0xFF;
            checksum += bytes[bytes.length - 1] & 0xFF;
        }
        return checksum;
    }

    /**
     * 执行解码基准。
     *
     * @param codecCase codec 用例；不可为空。
     * @param encodedSamples 编码样本；不可为空。
     * @param iterations 迭代次数。
     * @return 校验值。
     * @throws Exception 当解码失败时抛出。
     */
    private static long runDecode(
            final CodecCase codecCase,
            final byte[][] encodedSamples,
            final int iterations) throws Exception {
        long checksum = 0L;
        for (int index = 0; index < iterations; index++) {
            checksum += codecCase.decode(encodedSamples[index & (encodedSamples.length - 1)]);
        }
        return checksum;
    }

    /**
     * 执行热字段解码基准。
     *
     * @param codecCase codec 用例；不可为空。
     * @param encodedSamples 编码样本；不可为空。
     * @param iterations 迭代次数。
     * @return 校验值。
     * @throws Exception 当解码失败时抛出。
     */
    private static long runHotDecode(
            final CodecCase codecCase,
            final byte[][] encodedSamples,
            final int iterations) throws Exception {
        long checksum = 0L;
        for (int index = 0; index < iterations; index++) {
            checksum += codecCase.decodeHot(encodedSamples[index & (encodedSamples.length - 1)]);
        }
        return checksum;
    }

    /**
     * 执行编码后解码基准。
     *
     * @param codecCase codec 用例；不可为空。
     * @param samples 样本数组；不可为空。
     * @param iterations 迭代次数。
     * @return 校验值。
     * @throws Exception 当编解码失败时抛出。
     */
    private static long runRoundtrip(
            final CodecCase codecCase,
            final PayloadData[] samples,
            final int iterations) throws Exception {
        long checksum = 0L;
        for (int index = 0; index < iterations; index++) {
            byte[] bytes = codecCase.encode(samples[index & (samples.length - 1)]);
            checksum += bytes.length;
            checksum += codecCase.decode(bytes);
        }
        return checksum;
    }

    /**
     * 创建逻辑样本。
     *
     * @param sampleCount 样本数量，必须为 2 的幂。
     * @return 样本数组；不可为空。
     */
    private static PayloadData[] createSamples(final int sampleCount) {
        if (Integer.bitCount(sampleCount) != 1) {
            throw new IllegalArgumentException("sampleCount must be a power of two");
        }
        PayloadData[] samples = new PayloadData[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            samples[index] = createSample(index);
        }
        return samples;
    }

    /**
     * 创建单个逻辑样本。
     *
     * @param seed 样本种子。
     * @return 样本；不可为空。
     */
    private static PayloadData createSample(final int seed) {
        byte[] blob = new byte[64];
        for (int index = 0; index < blob.length; index++) {
            blob[index] = (byte) ((seed * 31 + index * 17) & 0xFF);
        }

        int[] stats = new int[16];
        for (int index = 0; index < stats.length; index++) {
            stats[index] = seed + index * 3 - 19;
        }

        long[] checkpoints = new long[8];
        for (int index = 0; index < checkpoints.length; index++) {
            checkpoints[index] = 1_700_000_000_000L + seed * 100L + index * 33L;
        }

        boolean[] flags = new boolean[12];
        for (int index = 0; index < flags.length; index++) {
            flags[index] = ((seed + index) & 1) == 0;
        }

        ItemData[] items = new ItemData[6];
        for (int index = 0; index < items.length; index++) {
            items[index] = new ItemData(
                    seed * 1000 + index,
                    (seed + index) & 127,
                    1_800_000_000_000L + seed * 1000L + index,
                    10.25D + index + seed * 0.01D,
                    "item-" + seed + '-' + index);
        }

        AttrData[] attrs = new AttrData[6];
        for (int index = 0; index < attrs.length; index++) {
            attrs[index] = new AttrData("attr-" + index, seed * 10 + index);
        }

        return new PayloadData(
                seed,
                9_000_000_000L + seed,
                100 + (seed & 15),
                12.5F + (seed & 7),
                -23.75D - (seed & 3),
                "player-" + seed,
                (seed & 1) == 0 ? null : "note-" + seed,
                blob,
                stats,
                checkpoints,
                items,
                attrs,
                flags);
    }

    /**
     * Zero Binary Protocol 用例。
     */
    private static final class ZeroCodecCase implements CodecCase {

        /**
         * 可复用写入器。
         */
        private final ZeroWriter writer = new ZeroWriter(512);

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空。
         */
        @Override
        public String name() {
            return "zero-proto";
        }

        /**
         * 编码样本。
         *
         * @param sample 样本；不可为空。
         * @return 字节数组；不可为空。
         */
        @Override
        public byte[] encode(final PayloadData sample) {
            writer.reset();
            writePayload(writer, sample);
            return writer.toByteArray();
        }

        /**
         * 解码并扫描全部字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         */
        @Override
        public long decode(final byte[] bytes) {
            ZeroReader reader = new ZeroReader(bytes);
            return readPayload(reader);
        }

        /**
         * 解码热字段并跳过对象尾部。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         */
        @Override
        public long decodeHot(final byte[] bytes) {
            ZeroReader reader = new ZeroReader(bytes);
            int objectEnd = reader.beginObject();
            long checksum = reader.readInt();
            checksum += reader.readLong();
            checksum += reader.readInt();
            reader.endObject(objectEnd);
            return checksum;
        }

        /**
         * 写入 payload。
         *
         * @param writer 写入器；不可为空。
         * @param sample 样本；不可为空。
         */
        private static void writePayload(final ZeroWriter writer, final PayloadData sample) {
            int marker = writer.beginObject();
            writer.writeInt(sample.sequence());
            writer.writeLong(sample.playerId());
            writer.writeInt(sample.sceneId());
            writer.writeFloat(sample.x());
            writer.writeDouble(sample.y());
            writer.writeString(sample.name());
            writer.writeNullableString(sample.note());
            writer.writeByteArray(sample.blob());
            writer.writeIntArray(sample.stats());
            writer.writeLongArray(sample.checkpoints());

            writer.writeUnsignedInt(sample.items().length);
            for (ItemData item : sample.items()) {
                writeItem(writer, item);
            }

            writer.writeUnsignedInt(sample.attrs().length);
            for (AttrData attr : sample.attrs()) {
                writer.writeString(attr.key());
                writer.writeInt(attr.value());
            }
            writer.writeBooleanArray(sample.flags());
            writer.endObject(marker);
        }

        /**
         * 写入子对象。
         *
         * @param writer 写入器；不可为空。
         * @param item 子对象；不可为空。
         */
        private static void writeItem(final ZeroWriter writer, final ItemData item) {
            int marker = writer.beginObject();
            writer.writeInt(item.itemId());
            writer.writeInt(item.count());
            writer.writeLong(item.expireAt());
            writer.writeDouble(item.price());
            writer.writeString(item.name());
            writer.endObject(marker);
        }

        /**
         * 读取并扫描 payload。
         *
         * @param reader 读取器；不可为空。
         * @return 校验值。
         */
        private static long readPayload(final ZeroReader reader) {
            int objectEnd = reader.beginObject();
            long checksum = 0L;
            checksum += reader.readInt();
            checksum += reader.readLong();
            checksum += reader.readInt();
            checksum += Float.floatToIntBits(reader.readFloat());
            checksum += Double.doubleToLongBits(reader.readDouble());
            checksum += reader.readString().length();
            String note = reader.readNullableString();
            checksum += note == null ? 17L : note.length();
            checksum += checksum(reader.readByteArray());
            checksum += checksum(reader.readIntArray());
            checksum += checksum(reader.readLongArray());

            int itemCount = reader.readUnsignedInt();
            for (int index = 0; index < itemCount; index++) {
                checksum += readItem(reader);
            }

            int attrCount = reader.readUnsignedInt();
            for (int index = 0; index < attrCount; index++) {
                checksum += reader.readString().length();
                checksum += reader.readInt();
            }
            checksum += checksum(reader.readBooleanArray());
            reader.endObject(objectEnd);
            return checksum;
        }

        /**
         * 读取并扫描子对象。
         *
         * @param reader 读取器；不可为空。
         * @return 校验值。
         */
        private static long readItem(final ZeroReader reader) {
            int itemEnd = reader.beginObject();
            long checksum = 0L;
            checksum += reader.readInt();
            checksum += reader.readInt();
            checksum += reader.readLong();
            checksum += Double.doubleToLongBits(reader.readDouble());
            checksum += reader.readString().length();
            reader.endObject(itemEnd);
            return checksum;
        }
    }

    /**
     * Protobuf 用例。
     */
    private static final class ProtobufCodecCase implements CodecCase {

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空。
         */
        @Override
        public String name() {
            return "protobuf";
        }

        /**
         * 编码样本。
         *
         * @param sample 样本；不可为空。
         * @return 字节数组；不可为空。
         */
        @Override
        public byte[] encode(final PayloadData sample) {
            CodecComparisonProto.SamplePayload.Builder builder =
                    CodecComparisonProto.SamplePayload.newBuilder()
                            .setSequence(sample.sequence())
                            .setPlayerId(sample.playerId())
                            .setSceneId(sample.sceneId())
                            .setX(sample.x())
                            .setY(sample.y())
                            .setName(sample.name())
                            .setBlob(ByteString.copyFrom(sample.blob()));
            if (sample.note() != null) {
                builder.setNote(sample.note());
            }
            for (int value : sample.stats()) {
                builder.addStats(value);
            }
            for (long value : sample.checkpoints()) {
                builder.addCheckpoints(value);
            }
            for (ItemData item : sample.items()) {
                builder.addItems(CodecComparisonProto.SampleItem.newBuilder()
                        .setItemId(item.itemId())
                        .setCount(item.count())
                        .setExpireAt(item.expireAt())
                        .setPrice(item.price())
                        .setName(item.name()));
            }
            for (AttrData attr : sample.attrs()) {
                builder.addAttrs(CodecComparisonProto.SampleAttr.newBuilder()
                        .setKey(attr.key())
                        .setValue(attr.value()));
            }
            for (boolean value : sample.flags()) {
                builder.addFlags(value);
            }
            return builder.build().toByteArray();
        }

        /**
         * 解码并扫描全部字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         * @throws InvalidProtocolBufferException 当 protobuf 解析失败时抛出。
         */
        @Override
        public long decode(final byte[] bytes) throws InvalidProtocolBufferException {
            CodecComparisonProto.SamplePayload payload =
                    CodecComparisonProto.SamplePayload.parseFrom(bytes);
            long checksum = 0L;
            checksum += payload.getSequence();
            checksum += payload.getPlayerId();
            checksum += payload.getSceneId();
            checksum += Float.floatToIntBits(payload.getX());
            checksum += Double.doubleToLongBits(payload.getY());
            checksum += payload.getName().length();
            checksum += payload.hasNote() ? payload.getNote().length() : 17L;
            checksum += payload.getBlob().size();
            for (int index = 0; index < payload.getBlob().size(); index++) {
                checksum += payload.getBlob().byteAt(index) & 0xFF;
            }
            for (int index = 0; index < payload.getStatsCount(); index++) {
                checksum += payload.getStats(index);
            }
            for (int index = 0; index < payload.getCheckpointsCount(); index++) {
                checksum += payload.getCheckpoints(index);
            }
            for (int index = 0; index < payload.getItemsCount(); index++) {
                CodecComparisonProto.SampleItem item = payload.getItems(index);
                checksum += item.getItemId();
                checksum += item.getCount();
                checksum += item.getExpireAt();
                checksum += Double.doubleToLongBits(item.getPrice());
                checksum += item.getName().length();
            }
            for (int index = 0; index < payload.getAttrsCount(); index++) {
                CodecComparisonProto.SampleAttr attr = payload.getAttrs(index);
                checksum += attr.getKey().length();
                checksum += attr.getValue();
            }
            for (int index = 0; index < payload.getFlagsCount(); index++) {
                checksum += payload.getFlags(index) ? 1L : 0L;
            }
            return checksum;
        }

        /**
         * 解码热字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         * @throws InvalidProtocolBufferException 当 protobuf 解析失败时抛出。
         */
        @Override
        public long decodeHot(final byte[] bytes) throws InvalidProtocolBufferException {
            CodecComparisonProto.SamplePayload payload =
                    CodecComparisonProto.SamplePayload.parseFrom(bytes);
            long checksum = payload.getSequence();
            checksum += payload.getPlayerId();
            checksum += payload.getSceneId();
            return checksum;
        }
    }

    /**
     * FlatBuffers 用例。
     */
    private static final class FlatBuffersCodecCase implements CodecCase {

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空。
         */
        @Override
        public String name() {
            return "flatbuffers";
        }

        /**
         * 编码样本。
         *
         * @param sample 样本；不可为空。
         * @return 字节数组；不可为空。
         */
        @Override
        public byte[] encode(final PayloadData sample) {
            FlatBufferBuilder builder = new FlatBufferBuilder(512);
            int nameOffset = builder.createString(sample.name());
            int noteOffset = sample.note() == null ? 0 : builder.createString(sample.note());
            int blobOffset = SamplePayload.createBlobVector(builder, sample.blob());
            int statsOffset = SamplePayload.createStatsVector(builder, sample.stats());
            int checkpointsOffset = SamplePayload.createCheckpointsVector(builder, sample.checkpoints());

            int[] itemOffsets = new int[sample.items().length];
            for (int index = 0; index < sample.items().length; index++) {
                ItemData item = sample.items()[index];
                int itemNameOffset = builder.createString(item.name());
                itemOffsets[index] = SampleItem.createSampleItem(
                        builder,
                        item.itemId(),
                        item.count(),
                        item.expireAt(),
                        item.price(),
                        itemNameOffset);
            }
            int itemsOffset = SamplePayload.createItemsVector(builder, itemOffsets);

            int[] attrOffsets = new int[sample.attrs().length];
            for (int index = 0; index < sample.attrs().length; index++) {
                AttrData attr = sample.attrs()[index];
                int keyOffset = builder.createString(attr.key());
                attrOffsets[index] = SampleAttr.createSampleAttr(builder, keyOffset, attr.value());
            }
            int attrsOffset = SamplePayload.createAttrsVector(builder, attrOffsets);
            int flagsOffset = SamplePayload.createFlagsVector(builder, sample.flags());

            int payloadOffset = SamplePayload.createSamplePayload(
                    builder,
                    sample.sequence(),
                    sample.playerId(),
                    sample.sceneId(),
                    sample.x(),
                    sample.y(),
                    nameOffset,
                    noteOffset,
                    blobOffset,
                    statsOffset,
                    checkpointsOffset,
                    itemsOffset,
                    attrsOffset,
                    flagsOffset);
            SamplePayload.finishSamplePayloadBuffer(builder, payloadOffset);
            return builder.sizedByteArray();
        }

        /**
         * 解码并扫描全部字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         */
        @Override
        public long decode(final byte[] bytes) {
            SamplePayload payload = SamplePayload.getRootAsSamplePayload(ByteBuffer.wrap(bytes));
            long checksum = 0L;
            checksum += payload.sequence();
            checksum += payload.playerId();
            checksum += payload.sceneId();
            checksum += Float.floatToIntBits(payload.x());
            checksum += Double.doubleToLongBits(payload.y());
            checksum += payload.name().length();
            String note = payload.note();
            checksum += note == null ? 17L : note.length();
            for (int index = 0; index < payload.blobLength(); index++) {
                checksum += payload.blob(index);
            }
            for (int index = 0; index < payload.statsLength(); index++) {
                checksum += payload.stats(index);
            }
            for (int index = 0; index < payload.checkpointsLength(); index++) {
                checksum += payload.checkpoints(index);
            }
            SampleItem item = new SampleItem();
            for (int index = 0; index < payload.itemsLength(); index++) {
                payload.items(item, index);
                checksum += item.itemId();
                checksum += item.count();
                checksum += item.expireAt();
                checksum += Double.doubleToLongBits(item.price());
                checksum += item.name().length();
            }
            SampleAttr attr = new SampleAttr();
            for (int index = 0; index < payload.attrsLength(); index++) {
                payload.attrs(attr, index);
                checksum += attr.key().length();
                checksum += attr.value();
            }
            for (int index = 0; index < payload.flagsLength(); index++) {
                checksum += payload.flags(index) ? 1L : 0L;
            }
            return checksum;
        }

        /**
         * 解码热字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         */
        @Override
        public long decodeHot(final byte[] bytes) {
            SamplePayload payload = SamplePayload.getRootAsSamplePayload(ByteBuffer.wrap(bytes));
            long checksum = payload.sequence();
            checksum += payload.playerId();
            checksum += payload.sceneId();
            return checksum;
        }
    }

    /**
     * 计算 byte 数组校验值。
     *
     * @param values byte 数组；不可为空。
     * @return 校验值。
     */
    private static long checksum(final byte[] values) {
        long checksum = values.length;
        for (byte value : values) {
            checksum += value & 0xFF;
        }
        return checksum;
    }

    /**
     * 计算 int 数组校验值。
     *
     * @param values int 数组；不可为空。
     * @return 校验值。
     */
    private static long checksum(final int[] values) {
        long checksum = values.length;
        for (int value : values) {
            checksum += value;
        }
        return checksum;
    }

    /**
     * 计算 long 数组校验值。
     *
     * @param values long 数组；不可为空。
     * @return 校验值。
     */
    private static long checksum(final long[] values) {
        long checksum = values.length;
        for (long value : values) {
            checksum += value;
        }
        return checksum;
    }

    /**
     * 计算 boolean 数组校验值。
     *
     * @param values boolean 数组；不可为空。
     * @return 校验值。
     */
    private static long checksum(final boolean[] values) {
        long checksum = values.length;
        for (boolean value : values) {
            checksum += value ? 1L : 0L;
        }
        return checksum;
    }

    /**
     * codec 用例抽象。
     */
    private interface CodecCase {

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空。
         */
        String name();

        /**
         * 编码样本。
         *
         * @param sample 样本；不可为空。
         * @return 字节数组；不可为空。
         * @throws Exception 当编码失败时抛出。
         */
        byte[] encode(PayloadData sample) throws Exception;

        /**
         * 解码并扫描全部字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         * @throws Exception 当解码失败时抛出。
         */
        long decode(byte[] bytes) throws Exception;

        /**
         * 解码热字段。
         *
         * @param bytes 字节数组；不可为空。
         * @return 校验值。
         * @throws Exception 当解码失败时抛出。
         */
        long decodeHot(byte[] bytes) throws Exception;
    }

    /**
     * benchmark 动作。
     */
    @FunctionalInterface
    private interface BenchmarkAction {

        /**
         * 执行动作。
         *
         * @return 校验值。
         * @throws Exception 当动作失败时抛出。
         */
        long run() throws Exception;
    }

    /**
     * 单项测量结果。
     *
     * @param nanosPerOp 平均 ns/op。
     * @param checksum 校验值。
     */
    private record Measure(double nanosPerOp, long checksum) {
    }

    /**
     * 总体 benchmark 结果。
     *
     * @param averageSize 平均 payload 字节数。
     * @param encodeNanos 编码 ns/op。
     * @param decodeNanos 解码 ns/op。
     * @param hotDecodeNanos 热字段解码 ns/op。
     * @param roundtripNanos 往返 ns/op。
     * @param checksum 校验值。
     */
    private record BenchmarkResult(
            double averageSize,
            double encodeNanos,
            double decodeNanos,
            double hotDecodeNanos,
            double roundtripNanos,
            long checksum) {
    }

    /**
     * 逻辑 payload 数据。
     *
     * @param sequence 序号。
     * @param playerId 玩家 ID。
     * @param sceneId 场景 ID。
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param name 名称。
     * @param note nullable 备注。
     * @param blob 字节块。
     * @param stats int 数组。
     * @param checkpoints long 数组。
     * @param items 子对象数组。
     * @param attrs 属性数组。
     * @param flags boolean 数组。
     */
    private record PayloadData(
            int sequence,
            long playerId,
            int sceneId,
            float x,
            double y,
            String name,
            String note,
            byte[] blob,
            int[] stats,
            long[] checkpoints,
            ItemData[] items,
            AttrData[] attrs,
            boolean[] flags) {

        /**
         * 创建 payload 数据。
         */
        private PayloadData {
            blob = Arrays.copyOf(blob, blob.length);
            stats = Arrays.copyOf(stats, stats.length);
            checkpoints = Arrays.copyOf(checkpoints, checkpoints.length);
            items = Arrays.copyOf(items, items.length);
            attrs = Arrays.copyOf(attrs, attrs.length);
            flags = Arrays.copyOf(flags, flags.length);
        }
    }

    /**
     * 子对象数据。
     *
     * @param itemId 道具 ID。
     * @param count 数量。
     * @param expireAt 过期时间。
     * @param price 价格。
     * @param name 名称。
     */
    private record ItemData(int itemId, int count, long expireAt, double price, String name) {
    }

    /**
     * 属性数据。
     *
     * @param key 属性名。
     * @param value 属性值。
     */
    private record AttrData(String key, int value) {
    }
}
