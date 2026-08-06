package group.zn.zero.net.http;

import java.util.concurrent.CompletionStage;

/**
 * HTTP 请求处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface HttpRequestHandler {

    /**
     * 处理 HTTP 请求。
     *
     * @param request HTTP 请求；不可为空。
     * @return HTTP 响应信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<HttpResponse> handle(HttpRequest request);
}
