package group.zn.zero.net.kcp;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JCA 内置明文、HMAC、ChaCha20-Poly1305 和 AES-GCM 策略。 @author zn */
public final class KcpProtectionStrategies {
    private KcpProtectionStrategies() { }
    /** @return 明文策略，仅限显式可信测试。 */
    public static KcpDatagramProtection none() { return Builtin.NONE; }
    /** @return HMAC-SHA256 策略，只认证不加密。 */
    public static KcpDatagramProtection hmac() { return Builtin.HMAC; }
    /** @return ChaCha20-Poly1305 策略。 */
    public static KcpDatagramProtection chacha20() { return Builtin.CHACHA; }
    /** @return AES-256-GCM 策略。 */
    public static KcpDatagramProtection aesGcm() { return Builtin.AES; }
    private enum Builtin implements KcpDatagramProtection {
        NONE(0, 0), HMAC(1, 32), CHACHA(2, 16), AES(3, 16);
        private final int id; private final int tag;
        Builtin(final int id, final int tag) { this.id = id; this.tag = tag; }
        public int wireId() { return id; } public int tagBytes() { return tag; }
        public boolean authenticated() { return id != 0; } public boolean encrypted() { return id >= 2; }
        public Context create(final byte[] key) { return new Crypto(this, key); }
    }
    private static final class Crypto implements KcpDatagramProtection.Context {
        private final Builtin algorithm; private final byte[] key; private final Cipher cipher; private final Mac mac; private boolean closed;
        Crypto(final Builtin algorithm, final byte[] key) {
            if (key == null || key.length != 32) throw new IllegalArgumentException("32-byte traffic key required");
            this.algorithm = algorithm; this.key = key.clone();
            try {
                cipher = algorithm.encrypted() ? Cipher.getInstance(algorithm == Builtin.AES ? "AES/GCM/NoPadding" : "ChaCha20-Poly1305") : null;
                mac = algorithm == Builtin.HMAC ? Mac.getInstance("HmacSHA256") : null;
                if (mac != null) mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            } catch (GeneralSecurityException failure) { throw new IllegalStateException("UDP protection unavailable", failure); }
        }
        public byte[] seal(final byte[] nonce, final byte[] aad, final byte[] body) {
            check(nonce); if (algorithm == Builtin.NONE) return body.clone();
            if (mac != null) { mac.update(aad); mac.update(body); byte[] tag = mac.doFinal(); byte[] result = Arrays.copyOf(body, body.length + tag.length); System.arraycopy(tag, 0, result, body.length, tag.length); return result; }
            try { return crypt(Cipher.ENCRYPT_MODE, nonce, aad, body); } catch (GeneralSecurityException failure) { throw new IllegalStateException("UDP sealing failed", failure); }
        }
        public byte[] open(final byte[] nonce, final byte[] aad, final byte[] body) {
            check(nonce); if (body.length < algorithm.tagBytes()) return null; if (algorithm == Builtin.NONE) return body.clone();
            if (mac != null) { int length = body.length - 32; mac.update(aad); mac.update(body, 0, length); return MessageDigest.isEqual(mac.doFinal(), Arrays.copyOfRange(body, length, body.length)) ? Arrays.copyOf(body, length) : null; }
            try { return crypt(Cipher.DECRYPT_MODE, nonce, aad, body); } catch (AEADBadTagException invalid) { return null; } catch (GeneralSecurityException failure) { throw new IllegalStateException("UDP opening failed", failure); }
        }
        private byte[] crypt(final int mode, final byte[] nonce, final byte[] aad, final byte[] body) throws GeneralSecurityException {
            Cipher operation = Cipher.getInstance(algorithm == Builtin.AES ? "AES/GCM/NoPadding" : "ChaCha20-Poly1305");
            if (algorithm == Builtin.AES) operation.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            else operation.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            operation.updateAAD(aad); return operation.doFinal(body);
        }
        private void check(final byte[] nonce) { if (closed) throw new IllegalStateException("UDP protection closed"); if (nonce == null || nonce.length != 12) throw new IllegalArgumentException("96-bit nonce required"); }
        public void close() { if (!closed) { closed = true; Arrays.fill(key, (byte) 0); } }
    }
}
