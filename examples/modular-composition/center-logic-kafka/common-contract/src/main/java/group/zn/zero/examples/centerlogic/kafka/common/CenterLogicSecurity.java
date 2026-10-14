package group.zn.zero.examples.centerlogic.kafka.common;

import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataVerifier;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 双 JVM 示例的服务身份配方；密钥由外部注入，签验复用框架实现。 @author zn */
public final class CenterLogicSecurity {
    /** 专用于该示例的 Base64 密钥环境变量，不写入日志或命令行。 */
    public static final String SECRET_ENV = "ZERO_CENTER_LOGIC_SECRET";
    /** 当前运行命名空间。 */
    private final String run;
    /** 进程内签名器。 */
    private final SecurityMetadataAssertion assertion;

    /** 创建线程安全配方；构造不连接 broker。
     * @param run 本次 topic prefix。 @param encodedSecret Base64 编码的至少 32 字节密钥。
     * @throws IllegalArgumentException 运行标识或密钥缺失/非法；错误不包含密钥。 */
    public CenterLogicSecurity(final String run, final String encodedSecret) {
        if (run == null || run.isBlank() || run.length() > 200) throw new IllegalArgumentException("invalid run prefix");
        this.run = run;
        if (encodedSecret == null || encodedSecret.isBlank()) throw new IllegalArgumentException(SECRET_ENV + " is required");
        byte[] secret;
        try { secret = Base64.getDecoder().decode(encodedSecret); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException(SECRET_ENV + " must be Base64"); }
        try { assertion = SecurityMetadataAssertion.digest(secret); }
        finally { java.util.Arrays.fill(secret, (byte) 0); }
    }

    /** 从显式外部材料创建配方；线程安全，不连接外部服务。
     * @param run 本次 topic prefix。 @return 配方，不可为空。
     * @throws IllegalArgumentException 环境变量缺失或非法。 */
    public static CenterLogicSecurity fromEnvironment(final String run) {
        return new CenterLogicSecurity(run, System.getenv(SECRET_ENV));
    }

    /** @return 签名端口；线程安全，不暴露密钥。 */
    public SecurityMetadataAssertion assertion() { return assertion; }

    /** @return 接收校验器；线程安全，仅接受当前运行的 Logic 服务身份与权限。 */
    public SecurityMetadataVerifier verifier() {
        var signatures = SecurityMetadataAssertion.verifier(assertion);
        return (snapshot, receivedAt) -> signatures.verify(snapshot, receivedAt).thenApply(context ->
                context != null && context.subject().equals("logic:" + run)
                        && context.allows("center:invoke") ? context : null);
    }

    /** 在短期 Logic 身份范围内调用 RPC，结束后恢复原线程身份；不传播密钥。
     * @param trace 当前调用 trace。 @param action 同步发起 RPC 的动作，不可为空。
     * @param <T> 调用结果类型。 @return 动作结果。
     * @throws RuntimeException 动作失败或身份参数非法，原异常直接传播。 */
    public <T> T asLogic(final String trace, final Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Instant now = Instant.now();
        var identity = new SecurityContext("logic:" + run, now, now.plusSeconds(60), "kafka",
                "local-service", "local-service", trace, trace, Set.of("center:invoke"), Map.of());
        return SecurityContextBridge.with(identity, () -> group.zn.zero.rpc.RpcCallContext.with(
                group.zn.zero.rpc.RpcCallContext.current().withTraceId(trace), action));
    }
}
