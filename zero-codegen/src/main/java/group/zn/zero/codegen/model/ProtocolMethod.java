package group.zn.zero.codegen.model;

import group.zn.zero.protocol.ProtocolDefinition;
import java.util.List;
import java.util.Objects;

/**
 * 协议方法定义。
 *
 * @param definition 协议定义。
 * @param requestMessage 请求消息名称。
 * @param responseMessage 响应消息名称。
 * @param boName 业务接口名称。
 * @param methodName 方法名称。
 * @param comment 方法注释。
 * @param parameters 方法参数列表。
 * @param expandedParameters BO 方法是否直接展开参数列表。
 * @author zn
 */
public record ProtocolMethod(
        ProtocolDefinition definition,
        String requestMessage,
        String responseMessage,
        String boName,
        String methodName,
        String comment,
        List<ProtocolField> parameters,
        boolean expandedParameters) {

    /**
     * 创建兼容旧块式 DSL 的协议方法定义。
     *
     * @param definition 协议定义。
     * @param requestMessage 请求消息名称。
     * @param responseMessage 响应消息名称。
     * @param boName 业务接口名称。
     * @param methodName 方法名称。
     * @param comment 方法注释。
     */
    public ProtocolMethod(
            final ProtocolDefinition definition,
            final String requestMessage,
            final String responseMessage,
            final String boName,
            final String methodName,
            final String comment) {
        this(definition, requestMessage, responseMessage, boName, methodName, comment, List.of(), false);
    }

    /**
     * 创建 `.si` 方法定义，BO 方法签名直接展开参数列表。
     *
     * @param definition 协议定义。
     * @param requestMessage 自动生成的请求消息名称。
     * @param responseMessage 响应消息名称。
     * @param boName 业务接口名称。
     * @param methodName 方法名称。
     * @param comment 方法注释。
     * @param parameters 方法参数列表。
     */
    public ProtocolMethod(
            final ProtocolDefinition definition,
            final String requestMessage,
            final String responseMessage,
            final String boName,
            final String methodName,
            final String comment,
            final List<ProtocolField> parameters) {
        this(definition, requestMessage, responseMessage, boName, methodName, comment, parameters, true);
    }

    /**
     * 创建协议方法定义。
     *
     * @throws NullPointerException 当协议定义、请求消息、业务接口名称或方法名称为空时抛出。
     * @throws IllegalArgumentException 当名称为空白时抛出。
     */
    public ProtocolMethod {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(requestMessage, "requestMessage");
        responseMessage = responseMessage == null ? "" : responseMessage;
        Objects.requireNonNull(boName, "boName");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(parameters, "parameters");
        comment = comment == null ? "" : comment;
        if (requestMessage.isBlank()) {
            throw new IllegalArgumentException("requestMessage must not be blank");
        }
        if (boName.isBlank()) {
            throw new IllegalArgumentException("boName must not be blank");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        parameters = List.copyOf(parameters);
    }

    /**
     * 返回协议方法参数列表。
     *
     * @return 不可变、有序、可能为空、线程安全的协议方法参数列表。
     */
    @Override
    public List<ProtocolField> parameters() {
        return parameters;
    }
}
