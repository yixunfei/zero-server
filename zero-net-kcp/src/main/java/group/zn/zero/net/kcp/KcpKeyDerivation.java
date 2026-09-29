package group.zn.zero.net.kcp;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF-SHA256 密钥派生；不保留调用方密钥。 @author zn */
final class KcpKeyDerivation {
    private KcpKeyDerivation() { }
    static byte[] derive(final byte[] ikm, final byte[] salt, final byte[] info, final int length) {
        if (ikm == null || salt == null || info == null || length < 1 || length > 8160) {
            throw new IllegalArgumentException("invalid HKDF input");
        }
        byte[] prk = null; byte[] previous = new byte[0];
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            prk = mac.doFinal(ikm); mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] result = new byte[length]; int offset = 0;
            for (int block = 1; offset < length; block++) {
                mac.update(previous); mac.update(info); mac.update((byte) block);
                Arrays.fill(previous, (byte) 0); previous = mac.doFinal();
                int count = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, result, offset, count); offset += count;
            }
            return result;
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HKDF unavailable", failure); }
        finally { if (prk != null) Arrays.fill(prk, (byte) 0); Arrays.fill(previous, (byte) 0); }
    }
}
