package group.zn.zero.protocol.buffer;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/**
 * Unsafe 访问工具。
 *
 * <p>该工具只供 native memory 缓冲区使用。Unsafe 路径不是默认协议实现，必须显式选择，
 * 并在后续通过 JMH 或压测验证收益。
 *
 * <p>FFM 迁移说明：Java 21 的 MemorySegment / Arena 属于第三次预览（JEP 442），
 * 正式 API 从 Java 22（JEP 454）开始。当前 Java 21 基线不启用 --enable-preview，
 * 因而暂保留这条显式选择的 Unsafe 路径。升级基线后应以 Arena 管理分配和释放，
 * 同时定义切片存活、扩容后旧视图、线程约束及关闭行为；不能仅机械替换 allocateMemory。
 * FFM 的安全检查与分配成本仍需实测，不应宣称所有场景零成本或绝无崩溃风险。
 *
 * @author zn
 */
final class ZeroUnsafe {

    /**
     * Unsafe 实例，可能为空。
     */
    private static final Unsafe UNSAFE = loadUnsafe();

    /**
     * byte[] 起始偏移。
     */
    static final long BYTE_ARRAY_OFFSET = UNSAFE == null ? -1L : UNSAFE.arrayBaseOffset(byte[].class);

    /**
     * 禁止实例化。
     */
    private ZeroUnsafe() {
    }

    /**
     * 返回 Unsafe 是否可用。
     *
     * @return true 表示可以使用 Unsafe；线程安全。
     */
    static boolean available() {
        return UNSAFE != null;
    }

    /**
     * 返回 Unsafe 实例。
     *
     * @return Unsafe 实例；不可为空。
     * @throws UnsupportedOperationException 当运行环境禁止访问 Unsafe 时抛出。
     */
    static Unsafe unsafe() {
        if (UNSAFE == null) {
            throw new UnsupportedOperationException("Unsafe is not available");
        }
        return UNSAFE;
    }

    /**
     * 尝试加载 Unsafe。
     *
     * @return Unsafe 实例；不可用时返回 null。
     */
    private static Unsafe loadUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException ex) {
            try {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                return (Unsafe) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }
}
