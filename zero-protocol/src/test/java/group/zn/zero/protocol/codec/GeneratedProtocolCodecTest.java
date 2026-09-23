package group.zn.zero.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 生成式协议编解码适配器测试。
 *
 * @author zn
 */
class GeneratedProtocolCodecTest {

    /**
     * 验证 payload codec 可以接入 ProtocolCodec。
     */
    @Test
    void payloadCodecShouldAdaptToProtocolCodec() {
        ProtocolDefinition definition = new ProtocolDefinition(
                1001,
                "sample.message",
                ProtocolDirection.CLIENT_TO_SERVER,
                1);
        GeneratedProtocolCodec<SampleMessage> codec = new GeneratedProtocolCodec<>(new SampleMessageCodec());

        byte[] payload = codec.encode(definition, new SampleMessage(7, "hello"));
        SampleMessage decoded = codec.decode(definition, payload, SampleMessage.class);

        assertEquals(new SampleMessage(7, "hello"), decoded);
    }

    /**
     * 验证复杂集合消息可以通过生成式 codec 往返。
     */
    @Test
    void collectionMessageShouldRoundTrip() {
        ProtocolDefinition definition = new ProtocolDefinition(
                1002,
                "full.message",
                ProtocolDirection.CLIENT_TO_SERVER,
                1);
        List<String> listData = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            listData.add("num:" + index);
        }
        Map<Integer, String> mapData = new LinkedHashMap<>();
        mapData.put(1, "one");
        mapData.put(2, "two");
        mapData.put(3, "three");
        Set<List<Integer>> setData = new LinkedHashSet<>();
        setData.add(List.of(1, 2, 3));
        setData.add(List.of(4, 5));

        SimpleMessage message = new SimpleMessage(
                123,
                "full data",
                listData,
                mapData,
                setData,
                new int[] {1, -2, 3},
                new int[][] {{7, 8}, {}, {-9}},
                new SampleMessage(Integer.MAX_VALUE, "objData"));
        GeneratedProtocolCodec<SimpleMessage> codec = new GeneratedProtocolCodec<>(new SimpleMessageCodec());

        byte[] payload = codec.encode(definition, message);
        SimpleMessage decoded = codec.decode(definition, payload, SimpleMessage.class);

        assertEquals(message, decoded);
        assertArrayEquals(message.numbers(), decoded.numbers());
        assertEquals(message.matrix().length, decoded.matrix().length);
    }

    /**
     * 验证解码目标类型必须匹配 payload codec。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mismatchedDecodeTypeShouldFail() {
        ProtocolDefinition definition = new ProtocolDefinition(
                1001,
                "sample.message",
                ProtocolDirection.CLIENT_TO_SERVER,
                1);
        GeneratedProtocolCodec codec = new GeneratedProtocolCodec<>(new SampleMessageCodec());
        byte[] payload = codec.encode(definition, new SampleMessage(7, "hello"));

        assertThrows(ZeroException.class, () -> codec.decode(definition, payload, OtherMessage.class));
    }

    /**
     * 验证 payload 尾部脏字节会被拒绝。
     */
    @Test
    void trailingPayloadBytesShouldFail() {
        ProtocolDefinition definition = new ProtocolDefinition(
                1001,
                "sample.message",
                ProtocolDirection.CLIENT_TO_SERVER,
                1);
        GeneratedProtocolCodec<SampleMessage> codec = new GeneratedProtocolCodec<>(new SampleMessageCodec());

        byte[] payload = codec.encode(definition, new SampleMessage(7, "hello"));
        byte[] dirtyPayload = java.util.Arrays.copyOf(payload, payload.length + 1);
        dirtyPayload[dirtyPayload.length - 1] = 1;

        assertThrows(ZeroException.class, () -> codec.decode(definition, dirtyPayload, SampleMessage.class));
    }

    /** 只读视图与数组入口共享类型/截断/尾随校验，来源游标保持不变。 */
    @Test void readOnlyViewMatchesArrayAndRejectsMalformedInput() {
        var definition = new ProtocolDefinition(1001, "sample", ProtocolDirection.CLIENT_TO_SERVER, 1);
        var codec = new GeneratedProtocolCodec<>(new SampleMessageCodec());
        byte[] payload = codec.encode(definition, new SampleMessage(7, "中文😀"));
        var frame = new group.zn.zero.protocol.ProtocolFrame(1001, 1, 0, null, payload);
        var view = frame.payloadView();
        assertEquals(new SampleMessage(7, "中文😀"), codec.decodeView(definition, view, SampleMessage.class));
        assertEquals(0, view.position());
        assertThrows(ZeroException.class, () -> codec.decodeView(definition,
                view.slice(0, payload.length - 1), SampleMessage.class));
        assertThrows(ZeroException.class, () -> codec.decodeView(definition,
                java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(payload, payload.length + 1)), SampleMessage.class));
    }

    /** 自定义 codec 只实现既有数组方法，默认视图适配仍可用且不能篡改帧。 */
    @Test void customCodecKeepsArrayFallback() {
        var definition = new ProtocolDefinition(1, "custom", ProtocolDirection.CLIENT_TO_SERVER, 1);
        ProtocolCodec<byte[]> custom = new ProtocolCodec<>() {
            @Override public String name() { return "custom"; }
            @Override public byte[] encode(final ProtocolDefinition type, final byte[] message) { return message.clone(); }
            @Override public byte[] decode(final ProtocolDefinition type, final byte[] payload, final Class<byte[]> target) {
                payload[0] = 9;
                return payload;
            }
        };
        var frame = new group.zn.zero.protocol.ProtocolFrame(1, 1, 0, null, new byte[] {1, 2});
        assertArrayEquals(new byte[] {9, 2}, custom.decodeView(definition, frame.payloadView(), byte[].class));
        assertArrayEquals(new byte[] {1, 2}, frame.payload());
    }

    /**
     * 测试消息。
     *
     * @param id ID。
     * @param name 名称。
     * @author zn
     */
    private record SampleMessage(int id, String name) {
    }

    private record SimpleMessage(
            int id,
            String name,
            List<String> listData,
            Map<Integer, String> mapData,
            Set<List<Integer>> collSetData,
            int[] numbers,
            int[][] matrix,
            SampleMessage objData) {

        /**
         * 比较消息内容。
         *
         * @param object 目标对象。
         * @return true 表示内容相同。
         */
        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof SimpleMessage other)) {
                return false;
            }
            return id == other.id
                    && java.util.Objects.equals(name, other.name)
                    && java.util.Objects.equals(listData, other.listData)
                    && java.util.Objects.equals(mapData, other.mapData)
                    && java.util.Objects.equals(collSetData, other.collSetData)
                    && java.util.Arrays.equals(numbers, other.numbers)
                    && java.util.Arrays.deepEquals(matrix, other.matrix)
                    && java.util.Objects.equals(objData, other.objData);
        }

        /**
         * 返回内容 hash。
         *
         * @return hash 值。
         */
        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(id, name, listData, mapData, collSetData, objData);
            result = 31 * result + java.util.Arrays.hashCode(numbers);
            result = 31 * result + java.util.Arrays.deepHashCode(matrix);
            return result;
        }
    }

    /**
     * 其他测试消息。
     *
     * @param id ID。
     * @author zn
     */
    private record OtherMessage(int id) {
    }

    /**
     * 测试消息 codec。
     *
     * @author zn
     */
    private static final class SampleMessageCodec implements ZeroPayloadCodec<SampleMessage> {

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "sample-message";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<SampleMessage> messageType() {
            return SampleMessage.class;
        }

        /**
         * 写入消息。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final SampleMessage message) {
            int marker = writer.beginObject();
            writer.writeInt(message.id());
            writer.writeString(message.name());
            writer.endObject(marker);
        }

        /**
         * 读取消息。
         *
         * @param reader 读取器；不可为空。
         * @return 消息；不可为空；线程安全。
         */
        @Override
        public SampleMessage read(final ZeroReader reader) {
            int end = reader.beginObject();
            int id = reader.readInt();
            String name = reader.readString();
            reader.endObject(end);
            return new SampleMessage(id, name);
        }
    }

    /**
     * 复杂集合消息 codec。
     *
     * @author zn
     */
    private static final class SimpleMessageCodec implements ZeroPayloadCodec<SimpleMessage> {

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "simple-message";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<SimpleMessage> messageType() {
            return SimpleMessage.class;
        }

        /**
         * 写入消息。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final SimpleMessage message) {
            int marker = writer.beginObject();
            writer.writeInt(message.id());
            writer.writeString(message.name());
            writer.writeCollection(message.listData(), ZeroWriter::writeString);
            writer.writeMap(message.mapData(), ZeroWriter::writeInt, ZeroWriter::writeString);
            writer.writeCollection(
                    message.collSetData(),
                    (out, list) -> out.writeCollection(list, ZeroWriter::writeInt));
            writer.writeIntArray(message.numbers());
            writer.writeArray(message.matrix(), ZeroWriter::writeIntArray);
            new SampleMessageCodec().write(writer, message.objData());
            writer.endObject(marker);
        }

        /**
         * 读取消息。
         *
         * @param reader 读取器；不可为空。
         * @return 消息；不可为空；线程安全。
         */
        @Override
        public SimpleMessage read(final ZeroReader reader) {
            int end = reader.beginObject();
            int id = reader.readInt();
            String name = reader.readString();
            List<String> listData = reader.readList(ZeroReader::readString);
            Map<Integer, String> mapData = reader.readMap(ZeroReader::readInt, ZeroReader::readString);
            Set<List<Integer>> collSetData = reader.readSet(in -> in.readList(ZeroReader::readInt));
            int[] numbers = reader.readIntArray();
            int[][] matrix = reader.readArray(int[].class, ZeroReader::readIntArray);
            SampleMessage objData = new SampleMessageCodec().read(reader);
            reader.endObject(end);
            return new SimpleMessage(id, name, listData, mapData, collSetData, numbers, matrix, objData);
        }
    }
}
