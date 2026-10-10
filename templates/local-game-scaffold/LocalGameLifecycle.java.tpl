package __PACKAGE__;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本地受管服务就绪/停服协调；只由主线程调用，不创建工作线程池。 @author zn */
public final class LocalGameLifecycle {
    private LocalGameLifecycle() { }

    /**
     * 发布实际 JVM 身份后等待停止文件或 JVM shutdown；退出前关闭服务并撤销就绪。
     * @param server 已成功启动、可幂等关闭的服务。 @param address 实际监听地址。
     * @throws Exception 文件或关闭失败；失败不会输出成功就绪。仅主线程调用。
     */
    public static void await(final AutoCloseable server, final String address) throws Exception {
        String control = System.getenv("ZERO_ENTRY_CONTROL");
        Path directory = control == null ? null : Path.of(control);
        String token = System.getenv("ZERO_ENTRY_TOKEN");
        if (directory != null && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("managed lifecycle requires control token");
        }
        AtomicBoolean closing = new AtomicBoolean();
        Thread hook = new Thread(() -> {
            try { closeOnce(server, directory, closing); }
            catch (Exception failure) {
                System.getLogger(LocalGameLifecycle.class.getName()).log(System.Logger.Level.ERROR,
                        "ZERO-SYSTEM-ERROR: shutdown cleanup failed", failure);
            }
        }, "zero-scaffold-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            if (directory != null) publish(directory, address);
            while (!stopRequested(directory, token)) Thread.sleep(100L);
        } finally {
            try {
                closeOnce(server, directory, closing);
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException shutdownInProgress) { hook.join(); }
            }
        }
    }

    private static void closeOnce(final AutoCloseable server, final Path directory,
            final AtomicBoolean closing) throws Exception {
        // shutdown hook 必须等待主线程正在执行的关闭，不能提前返回让 JVM 中断清理。
        synchronized (closing) {
            if (closing.compareAndSet(false, true)) {
                try { withdraw(directory); } finally { server.close(); }
            }
        }
    }

    private static boolean stopRequested(final Path directory, final String token) throws Exception {
        if (directory == null) return false;
        Path stop = directory.resolve("stop");
        return Files.isRegularFile(stop) && token.equals(Files.readString(stop));
    }

    private static void publish(final Path directory, final String address) throws Exception {
        Properties state = new Properties();
        ProcessHandle current = ProcessHandle.current();
        state.setProperty("pid", Long.toString(current.pid()));
        state.setProperty("start", current.info().startInstant().orElseThrow().toString());
        state.setProperty("token", System.getenv("ZERO_ENTRY_TOKEN"));
        state.setProperty("address", address);
        Path temporary = directory.resolve("ready.tmp");
        try (var output = Files.newOutputStream(temporary)) { state.store(output, "local server ready"); }
        Files.move(temporary, directory.resolve("ready"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void withdraw(final Path directory) throws Exception {
        if (directory != null) {
            Files.deleteIfExists(directory.resolve("ready"));
            Files.deleteIfExists(directory.resolve("ready.tmp"));
            Files.deleteIfExists(directory.resolve("stop"));
        }
    }
}
