package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AES/ChaCha 密码实例复用及认证失败后上下文恢复回归。 @author zn */
class KcpProtectionReuseTest {
    /** 每个 Context 只创建一次方向 Cipher，连续包保持不同 nonce 的加解密正确。 */
    @Test
    void ciphersAndKeysAreReusedAcrossDatagrams() throws Exception {
        for (KcpDatagramProtection strategy : strategies()) {
            try (var context = strategy.create(new byte[32])) {
                Object seal = field(context, "sealCipher");
                Object open = field(context, "openCipher");
                Object key = field(context, "keySpec");
                for (int sequence = 0; sequence < 128; sequence++) {
                    byte[] nonce = ByteBuffer.allocate(12).putInt(3).putLong(sequence).array();
                    byte[] body = ByteBuffer.allocate(8).putLong(sequence).array();
                    byte[] sealed = context.seal(nonce, new byte[] {4}, body);
                    assertArrayEquals(body, context.open(nonce, new byte[] {4}, sealed));
                    assertSame(seal, field(context, "sealCipher"));
                    assertSame(open, field(context, "openCipher"));
                    assertSame(key, field(context, "keySpec"));
                }
            }
        }
    }

    /** 篡改或错误 AAD 不能消耗接收 nonce；原包在同一 Context 上仍能认证。 */
    @Test
    void failedAuthenticationAllowsOriginalDatagram() {
        for (KcpDatagramProtection strategy : strategies()) {
            try (var context = strategy.create(new byte[32])) {
                byte[] nonce = new byte[12];
                byte[] aad = {1, 2};
                byte[] body = {3, 4, 5};
                byte[] sealed = context.seal(nonce, aad, body);
                byte[] corrupted = sealed.clone();
                corrupted[0] ^= 1;
                assertNull(context.open(nonce, aad, corrupted));
                assertNull(context.open(nonce, new byte[] {9}, sealed));
                assertArrayEquals(body, context.open(nonce, aad, sealed));
            }
        }
    }

    /** 关闭后清除框架持有密钥副本和复用引用，并拒绝继续使用。 */
    @Test
    void closeClearsOwnedKeyAndRejectsFurtherUse() throws Exception {
        for (KcpDatagramProtection strategy : strategies()) {
            var context = strategy.create(new byte[32]);
            context.close();
            context.close();
            assertArrayEquals(new byte[32], (byte[]) field(context, "key"));
            assertNull(field(context, "keySpec"));
            assertNull(field(context, "sealCipher"));
            assertNull(field(context, "openCipher"));
            assertThrows(IllegalStateException.class,
                    () -> context.seal(new byte[12], new byte[0], new byte[0]));
        }
    }

    /** 返回本次修改涉及的两种加密策略。 */
    private List<KcpDatagramProtection> strategies() {
        return List.of(KcpProtectionStrategies.aesGcm(), KcpProtectionStrategies.chacha20());
    }

    /** 直接验证资源身份，防止 round-trip 正常但仍每包创建 JCA 实例。 */
    private Object field(final Object context, final String name) throws Exception {
        Field field = context.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(context);
    }
}
