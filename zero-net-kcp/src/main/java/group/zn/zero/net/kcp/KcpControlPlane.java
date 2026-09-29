package group.zn.zero.net.kcp;

import java.util.concurrent.CompletionStage;

/** 已认证 TLS 控制面的项目适配口；实现负责协议 ID、响应超时和安全校验。 @author zn */
public interface KcpControlPlane {
    /** @return 新的权威连接描述，撤销旧票据；线程安全，不重放业务。 */
    CompletionStage<KcpConnectInfo> acquire();
    /** @param conv 待撤销授权。 @return 服务端本地撤销确认；线程安全，不表示事务完成。 */
    CompletionStage<Void> revoke(int conv);
}
