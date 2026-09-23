package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Netty heap/direct/composite 的双向重叠搬移参考模型。 @author zn */
class NettyBufferMoveTest {
    /** 复用的 NIO 视图在 ByteBuf 扩容后不能指向旧分配区。 */
    @Test void expansionInvalidatesMoveView() {
        ByteBuf input = Unpooled.directBuffer(8);
        try {
            var buffer = new NettyZeroBuffer(input, 64);
            buffer.putBytes(0, new byte[] {1, 2, 3, 4}, 0, 4);
            buffer.copy(0, 1, 3);
            buffer.ensureCapacity(64);
            byte[] model = new byte[64];
            new Random(19).nextBytes(model);
            buffer.putBytes(0, model, 0, 64);
            System.arraycopy(model, 5, model, 8, 40);
            buffer.copy(5, 8, 40);
            assertArrayEquals(model, buffer.toByteArray(0, 64));
        } finally { input.release(); }
    }

    /** 测试 slice 偏移和 composite fallback，结束必须释放所有缓冲。 */
    @Test void movesMatchArrayCopyAcrossBackends() {
        ByteBuf composite = Unpooled.compositeBuffer().addComponents(true,
                Unpooled.buffer(128).writeZero(128), Unpooled.directBuffer(128).writeZero(128));
        ByteBuf[] inputs = {Unpooled.buffer(300).slice(17, 256),
                Unpooled.directBuffer(300).slice(17, 256), composite};
        for (ByteBuf input : inputs) {
            try {
                var buffer = new NettyZeroBuffer(input, 256);
                Random random = new Random(1729);
                for (int i = 0; i < 500; i++) {
                    byte[] model = new byte[256];
                    random.nextBytes(model);
                    buffer.putBytes(0, model, 0, model.length);
                    int count = random.nextInt(257);
                    int from = random.nextInt(257 - count);
                    int to = random.nextInt(257 - count);
                    System.arraycopy(model, from, model, to, count);
                    buffer.copy(from, to, count);
                    assertArrayEquals(model, buffer.toByteArray(0, 256));
                }
            } finally { input.release(); }
        }
    }
}
