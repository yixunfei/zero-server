package group.zn.zero.runtime.kafka;

import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.spi.RpcHandler;
import java.util.concurrent.CompletionStage;
import java.util.Objects;
import java.util.Optional;

/**
 * 创建可在发布前完成 handler 回放和失败回滚的 Kafka RPC 资源。
 *
 * <p>该协作者把 producer/consumer 资源创建与 lifecycle 发布动作分开，使回放中途失败时仍可关闭尚未
 * 发布的 Adapter。接口只服务 production starter 冷启动路径，不改变 Kafka 模块依赖方向。
 *
 * @author zn
 */
@FunctionalInterface
interface KafkaRpcAdapterResourceFactory {

    /**
     * 创建尚未发布的 Kafka RPC 资源。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @return 尚未发布的资源；不可为空，调用方负责发布或关闭。
     * @throws RuntimeException 底层 Kafka 资源创建失败时抛出。
     * @throws Error 底层严重资源创建失败时抛出。
     */
    KafkaRpcAdapterResource create(KafkaRpcSettings settings, RpcTransportObserver observer);

    /**
     * 返回创建真实 Kafka RPC Adapter 的生产工厂。
     *
     * @return 生产资源工厂；不可为空，无状态且线程安全。
     */
    static KafkaRpcAdapterResourceFactory production() {
        return DefaultKafkaRpcAdapterResource::new;
    }
}

/**
 * Kafka RPC Adapter 发布前资源句柄。
 *
 * @author zn
 */
interface KafkaRpcAdapterResource extends AutoCloseable {

    /**
     * 回放一个延迟注册的 RPC handler。
     *
     * <p>线程安全性由真实 Adapter 保证；调用会更新 handler 与 subscription 状态。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param topic request topic；不可为空，可为空串。
     * @param group consumer group；不可为空，可为空串。
     * @param handler RPC handler；不可为空。
     * @throws RuntimeException 注册或 subscription 创建失败时抛出。
     * @throws Error 底层严重资源失败时抛出。
     */
    void register(String serviceName, String methodName, String topic, String group, RpcHandler handler);

    /**
     * 注销一个已经发布或回放的 RPC handler。
     *
     * <p>线程安全性由真实 Adapter 保证；调用会移除 handler 与关联 subscription。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @throws RuntimeException 注销或 subscription 释放失败时抛出。
     * @throws Error 底层严重资源失败时抛出。
     */
    void unregister(String serviceName, String methodName);

    /**
     * 发送 request/response 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应阶段；不可为空，线程安全性由底层 Adapter 声明。
     * @throws RuntimeException 资源不可用或发送失败时抛出。
     * @throws Error 底层严重资源失败时抛出。
     */
    CompletionStage<RpcResponse> request(RpcRequest request);

    /**
     * 发送 oneway 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成阶段；不可为空，线程安全性由底层 Adapter 声明。
     * @throws RuntimeException 资源不可用或发送失败时抛出。
     * @throws Error 底层严重资源失败时抛出。
     */
    CompletionStage<Void> oneway(RpcRequest request);

    /**
     * 返回全部 handler 回放成功后可公开的真实 Adapter。
     *
     * <p>生产资源始终返回真实 Adapter；包级故障注入资源可不公开底层实现。
     *
     * @return 可公开 Adapter；不可为空，生产资源非空，线程安全。
     */
    Optional<KafkaRpcAdapter> exposedAdapter();

    /**
     * 关闭尚未发布或需要正常停止的底层资源。
     *
     * @throws RuntimeException 关闭失败时抛出。
     * @throws Error 底层严重关闭失败时抛出。
     */
    @Override
    void close();
}

/**
 * 真实 Kafka RPC Adapter 的默认资源句柄。
 *
 * @author zn
 */
final class DefaultKafkaRpcAdapterResource implements KafkaRpcAdapterResource {

    /** 真实 Kafka RPC Adapter。 */
    private final KafkaRpcAdapter adapter;

    /**
     * 创建真实 Kafka RPC Adapter 资源。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     */
    DefaultKafkaRpcAdapterResource(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer) {
        this.adapter = new KafkaRpcAdapter(
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(observer, "observer"));
    }

    /**
     * 把延迟 handler 注册回放到真实 Adapter。
     *
     * <p>线程安全性由真实 Adapter 保证；调用会更新 handler 与 subscription 状态。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param topic request topic；不可为空，可为空串。
     * @param group consumer group；不可为空，可为空串。
     * @param handler RPC handler；不可为空。
     * @throws RuntimeException 参数非法或真实注册失败时抛出。
     */
    @Override
    public void register(
            final String serviceName,
            final String methodName,
            final String topic,
            final String group,
            final RpcHandler handler) {
        adapter.register(serviceName, methodName, topic, group, handler);
    }

    /**
     * 注销真实 Adapter 中的 handler。
     *
     * <p>线程安全性由真实 Adapter 保证；调用会移除 handler 与关联 subscription。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @throws RuntimeException 参数非法或真实注销失败时抛出。
     */
    @Override
    public void unregister(final String serviceName, final String methodName) {
        adapter.unregister(serviceName, methodName);
    }

    /**
     * 通过真实 Adapter 发送 request/response 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应阶段；不可为空，线程安全。
     * @throws RuntimeException 请求非法或发送失败时抛出。
     */
    @Override
    public CompletionStage<RpcResponse> request(final RpcRequest request) {
        return adapter.request(request);
    }

    /**
     * 通过真实 Adapter 发送 oneway 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成阶段；不可为空，线程安全。
     * @throws RuntimeException 请求非法或发送失败时抛出。
     */
    @Override
    public CompletionStage<Void> oneway(final RpcRequest request) {
        return adapter.oneway(request);
    }

    /**
     * 返回可公开的真实 Adapter。
     *
     * @return 包含真实 Kafka RPC Adapter 的只读容器；不可为空，线程安全。
     */
    @Override
    public Optional<KafkaRpcAdapter> exposedAdapter() {
        return Optional.of(adapter);
    }

    /**
     * 幂等关闭真实 Kafka RPC Adapter；线程安全，不修改业务数据。
     *
     * @throws RuntimeException 底层资源关闭失败时抛出，由上层转换为安全异常。
     */
    @Override
    public void close() {
        adapter.close();
    }
}
