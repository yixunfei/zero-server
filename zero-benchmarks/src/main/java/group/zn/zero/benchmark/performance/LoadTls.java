package group.zn.zero.benchmark.performance;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 显式本机 TLS 负载证书；仅信任本次生成证书，不修改主机信任库。 @author zn */
final class LoadTls {
    /** 仅供临时测试证书文件的固定口令，不是生产凭据。 */
    private static final char[] PASSWORD = "zero-benchmark".toCharArray();
    /** 每进程一次初始化客户端信任上下文。 */
    private static final SSLContext CLIENT = clientContext();
    private LoadTls() { }

    static SslContext serverContext() throws Exception {
        if (System.getProperty("zero.load.tls.keyStore") == null) return null;
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store(), PASSWORD);
        return SslContextBuilder.forServer(keys).build();
    }

    static Socket socket() throws java.io.IOException {
        return CLIENT == null ? new Socket() : CLIENT.getSocketFactory().createSocket();
    }

    private static SSLContext clientContext() {
        if (System.getProperty("zero.load.tls.keyStore") == null) return null;
        try {
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store());
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception failure) { throw new IllegalStateException("load TLS initialization failed", failure); }
    }

    private static KeyStore store() throws Exception {
        var result = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(System.getProperty("zero.load.tls.keyStore")))) {
            result.load(input, PASSWORD);
        }
        return result;
    }
}
