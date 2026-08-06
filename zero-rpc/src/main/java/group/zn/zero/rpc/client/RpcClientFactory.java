package group.zn.zero.rpc.client;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.DefaultRpcCorrelationIdGenerator;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcCorrelationIdGenerator;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.codec.RpcPayloadCodec;
import group.zn.zero.rpc.common.RpcCallMode;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.descriptor.RpcMethodDescriptor;
import group.zn.zero.rpc.descriptor.RpcServiceDescriptor;
import group.zn.zero.rpc.descriptor.RpcServiceIntrospector;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcTransport;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * RPC common 接口客户端代理工厂。
 *
 * @author zn
 */
public final class RpcClientFactory {

    /**
     * RPC 传输。
     */
    private final RpcTransport transport;

    /**
     * RPC codec 注册表。
     */
    private final RpcCodecRegistry codecRegistry;

    /**
     * 默认调用选项。
     */
    private final RpcCallOptions defaultOptions;

    /**
     * correlationId 生成器。
     */
    private final RpcCorrelationIdGenerator correlationIdGenerator;

    /**
     * 接口类型到服务描述符的实例级缓存。
     */
    private final ConcurrentMap<Class<?>, RpcServiceDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * 接口类型和调用选项到代理对象的实例级缓存。
     */
    private final ConcurrentMap<ClientKey, Object> clients = new ConcurrentHashMap<>();

    /**
     * 创建 RPC common 接口客户端代理工厂。
     *
     * @param transport RPC 传输；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @throws NullPointerException 当 RPC 传输或 codec 注册表为空时抛出。
     */
    public RpcClientFactory(final RpcTransport transport, final RpcCodecRegistry codecRegistry) {
        this(transport, codecRegistry, RpcCallOptions.defaults());
    }

    /**
     * 创建 RPC common 接口客户端代理工厂。
     *
     * @param transport RPC 传输；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @param correlationIdGenerator correlationId 生成器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcClientFactory(
            final RpcTransport transport,
            final RpcCodecRegistry codecRegistry,
            final RpcCorrelationIdGenerator correlationIdGenerator) {
        this(transport, codecRegistry, RpcCallOptions.defaults(), correlationIdGenerator);
    }

    /**
     * 创建 RPC common 接口客户端代理工厂。
     *
     * @param transport RPC 传输；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @param defaultOptions 默认调用选项；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcClientFactory(
            final RpcTransport transport,
            final RpcCodecRegistry codecRegistry,
            final RpcCallOptions defaultOptions) {
        this(transport, codecRegistry, defaultOptions, defaultGenerator(transport));
    }

    /**
     * 创建 RPC common 接口客户端代理工厂。
     *
     * @param transport RPC 传输；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @param defaultOptions 默认调用选项；不可为空。
     * @param correlationIdGenerator correlationId 生成器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcClientFactory(
            final RpcTransport transport,
            final RpcCodecRegistry codecRegistry,
            final RpcCallOptions defaultOptions,
            final RpcCorrelationIdGenerator correlationIdGenerator) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
        this.defaultOptions = Objects.requireNonNull(defaultOptions, "defaultOptions");
        this.correlationIdGenerator = Objects.requireNonNull(correlationIdGenerator, "correlationIdGenerator");
    }

    /**
     * 创建 RPC common 接口代理。
     *
     * @param serviceInterface 服务接口类型；不可为空。
     * @param <T> 服务接口类型。
     * @return 服务接口代理；不可为空；线程安全。
     * @throws ZeroException 当接口契约非法或 codec 缺失时抛出。
     */
    public <T> T create(final Class<T> serviceInterface) {
        return create(serviceInterface, defaultOptions);
    }

    /**
     * 创建带固定调用选项的 RPC common 接口代理。
     *
     * @param serviceInterface 服务接口类型；不可为空。
     * @param options 固定调用选项；不可为空。
     * @param <T> 服务接口类型。
     * @return 服务接口代理；不可为空；线程安全。
     * @throws ZeroException 当接口契约非法或 codec 缺失时抛出。
     */
    public <T> T create(final Class<T> serviceInterface, final RpcCallOptions options) {
        Class<T> currentInterface = Objects.requireNonNull(serviceInterface, "serviceInterface");
        RpcCallOptions currentOptions = Objects.requireNonNull(options, "options");
        Object proxy = clients.computeIfAbsent(
                new ClientKey(currentInterface, currentOptions),
                ignored -> newClient(currentInterface, currentOptions));
        return currentInterface.cast(proxy);
    }

    private <T> Object newClient(final Class<T> serviceInterface, final RpcCallOptions options) {
        RpcServiceDescriptor descriptor = describe(serviceInterface);
        InvocationHandler handler = new CommonInterfaceInvocationHandler(
                transport,
                descriptor,
                options,
                correlationIdGenerator);
        Object proxy = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface},
                handler);
        return proxy;
    }

    private RpcServiceDescriptor describe(final Class<?> serviceInterface) {
        return descriptors.computeIfAbsent(serviceInterface,
                current -> RpcServiceIntrospector.describe(current, codecRegistry));
    }

    private static RpcCorrelationIdGenerator defaultGenerator(final RpcTransport transport) {
        RpcTransport current = Objects.requireNonNull(transport, "transport");
        return new DefaultRpcCorrelationIdGenerator(current.name());
    }

    /**
     * RPC client 缓存键。
     *
     * @param serviceInterface 服务接口类型。
     * @param options 固定调用选项。
     */
    private record ClientKey(Class<?> serviceInterface, RpcCallOptions options) {

        private ClientKey {
            Objects.requireNonNull(serviceInterface, "serviceInterface");
            Objects.requireNonNull(options, "options");
        }
    }

    /**
     * common 接口代理调用处理器。
     *
     * @author zn
     */
    private static final class CommonInterfaceInvocationHandler implements InvocationHandler {

        /**
         * RPC 传输。
         */
        private final RpcTransport transport;

        /**
         * 服务描述符。
         */
        private final RpcServiceDescriptor service;

        /**
         * 调用选项。
         */
        private final RpcCallOptions options;

        /**
         * correlationId 生成器。
         */
        private final RpcCorrelationIdGenerator correlationIdGenerator;

        /**
         * Java 方法到分区键解析器的映射。
         */
        private final Map<Method, MethodBinding> bindings;

        private CommonInterfaceInvocationHandler(
                final RpcTransport transport,
                final RpcServiceDescriptor service,
                final RpcCallOptions options,
                final RpcCorrelationIdGenerator correlationIdGenerator) {
            this.transport = transport;
            this.service = service;
            this.options = options;
            this.correlationIdGenerator = correlationIdGenerator;
            this.bindings = compileBindings(service);
        }

        private Map<Method, MethodBinding> compileBindings(final RpcServiceDescriptor service) {
            java.util.HashMap<Method, MethodBinding> resolvers = new java.util.HashMap<>();
            for (RpcMethodDescriptor method : service.methods()) {
                resolvers.put(method.javaMethod(), new MethodBinding(method, PartitionKeyResolver.compile(method)));
            }
            return Map.copyOf(resolvers);
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            if (method.getDeclaringClass().equals(Object.class)) {
                return handleObjectMethod(proxy, method, args);
            }
            MethodBinding binding = bindings.get(method);
            if (binding == null) {
                throw ZeroException.of(
                        RpcErrorCode.CONTRACT_INVALID,
                        "method is not an rpc method: " + method.getName(),
                        null);
            }
            CompletionStage<RpcResult<Object>> stage = invokeRemote(binding, args);
            if (binding.descriptor().asyncReturn()) {
                return stage;
            }
            return waitResult(binding.descriptor(), stage);
        }

        private CompletionStage<RpcResult<Object>> invokeRemote(
                final MethodBinding binding,
                final Object[] args) {
            RpcMethodDescriptor descriptor = binding.descriptor();
            RpcCallContext context = RpcCallContext.current();
            String correlationId = correlationIdGenerator.nextCorrelationId();
            String replyTopic = context.resolveReplyTopic(options);
            String traceId = context.resolveTraceId(options);
            long timeoutMillis = context.resolveTimeoutMillis(options, descriptor.timeoutMillis());
            RpcRequest request = new RpcRequest(
                    correlationId,
                    replyTopic,
                    descriptor.routeServiceName(),
                    descriptor.routeMethodName(),
                    traceId,
                    Instant.now().plusMillis(timeoutMillis),
                    descriptor.transportMode(),
                    descriptor.topic(),
                    descriptor.group(),
                    binding.resolvePartitionKey(args),
                    RpcPayloadCodec.encodeArguments(descriptor, args));
            try {
                if (descriptor.callMode() == RpcCallMode.ONEWAY) {
                    return transport.oneway(request)
                            .thenApply(ignored -> successResult(descriptor, null, request));
                }
                return transport.request(request)
                        .thenApply(response -> responseResult(descriptor, request, response))
                        .exceptionally(ex -> failureResult(descriptor, request, unwrap(ex)));
            } catch (RuntimeException ex) {
                return CompletableFuture.completedFuture(failureResult(descriptor, request, ex));
            }
        }

        private Object waitResult(
                final RpcMethodDescriptor descriptor,
                final CompletionStage<RpcResult<Object>> stage) {
            try {
                long timeoutMillis = RpcCallContext.current().resolveTimeoutMillis(options, descriptor.timeoutMillis());
                return stage.toCompletableFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return RpcResult.failure(RpcErrorCode.REQUEST_TIMEOUT, "rpc invocation interrupted");
            } catch (java.util.concurrent.TimeoutException ex) {
                return RpcResult.failure(RpcErrorCode.REQUEST_TIMEOUT, "rpc invocation timed out");
            } catch (java.util.concurrent.ExecutionException ex) {
                return failureResult(descriptor, null, unwrap(ex));
            }
        }

        private Object handleObjectMethod(final Object proxy, final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "RpcClientProxy{" + service.serviceName() + ":v" + service.serviceVersion() + '}';
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw ZeroException.of(
                        RpcErrorCode.INVOCATION_FAILED,
                        "unsupported Object method: " + method.getName(),
                        null);
            };
        }

        private RpcResult<Object> responseResult(
                final RpcMethodDescriptor descriptor,
                final RpcRequest request,
                final RpcResponse response) {
            if (!SystemErrorCode.OK.code().equals(response.errorCode().code())) {
                return failureResult(descriptor, request, response.errorCode(), response.errorMessage());
            }
            Object result = RpcPayloadCodec.decodeResult(descriptor, response.payload());
            return successResult(descriptor, result, request);
        }

        private RpcResult<Object> successResult(
                final RpcMethodDescriptor descriptor,
                final Object result,
                final RpcRequest request) {
            return RpcResult.success(result).withRemoteContext(
                    request.traceId(),
                    request.correlationId(),
                    descriptor.serviceName(),
                    descriptor.methodName());
        }

        private RpcResult<Object> failureResult(
                final RpcMethodDescriptor descriptor,
                final RpcRequest request,
                final Throwable throwable) {
            Throwable current = unwrap(throwable);
            if (current instanceof ZeroException zeroException) {
                return failureResult(descriptor, request, zeroException.errorCode(), zeroException.message())
                        .withExceptionClass(zeroException.getClass().getName());
            }
            String message = current == null || current.getMessage() == null
                    ? RpcErrorCode.INVOCATION_FAILED.message()
                    : current.getMessage();
            return failureResult(descriptor, request, RpcErrorCode.INVOCATION_FAILED, message)
                    .withExceptionClass(current == null ? "" : current.getClass().getName());
        }

        private RpcResult<Object> failureResult(
                final RpcMethodDescriptor descriptor,
                final RpcRequest request,
                final ErrorCode errorCode,
                final String message) {
            String traceId = request == null ? RpcCallContext.current().resolveTraceId(options) : request.traceId();
            String correlationId = request == null ? "" : request.correlationId();
            return RpcResult.failure(errorCode, message).withRemoteContext(
                    traceId,
                    correlationId,
                    descriptor.serviceName(),
                    descriptor.methodName());
        }

        private Throwable unwrap(final Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) {
                if (current.getCause() == null) {
                    break;
                }
                current = current.getCause();
            }
            return current;
        }

        private record MethodBinding(RpcMethodDescriptor descriptor, PartitionKeyResolver partitionKeyResolver) {

            private MethodBinding {
                Objects.requireNonNull(descriptor, "descriptor");
                Objects.requireNonNull(partitionKeyResolver, "partitionKeyResolver");
            }

            private String resolvePartitionKey(final Object[] args) {
                return partitionKeyResolver.resolve(args);
            }
        }

        /**
         * partitionKey 表达式解析器。
         *
         * <p>解析器在代理创建阶段完成表达式校验和访问器缓存，调用热路径只读取参数并执行缓存访问器。
         */
        private static final class PartitionKeyResolver {

            /**
             * 原始表达式。
             */
            private final String expression;

            /**
             * 参数下标。
             */
            private final int argumentIndex;

            /**
             * 是否作为字面量返回。
             */
            private final boolean literal;

            /**
             * 缓存访问器列表。
             */
            private final List<Accessor> accessors;

            private PartitionKeyResolver(
                    final String expression,
                    final int argumentIndex,
                    final boolean literal,
                    final List<Accessor> accessors) {
                this.expression = Objects.requireNonNull(expression, "expression");
                this.argumentIndex = argumentIndex;
                this.literal = literal;
                this.accessors = List.copyOf(accessors);
            }

            /**
             * 编译方法分区键解析器。
             *
             * @param descriptor RPC 方法描述符；不可为空。
             * @return 分区键解析器；不可为空；线程安全。
             */
            static PartitionKeyResolver compile(final RpcMethodDescriptor descriptor) {
                RpcMethodDescriptor current = Objects.requireNonNull(descriptor, "descriptor");
                String expression = current.partitionKey().trim();
                if (expression.isBlank()) {
                    return new PartitionKeyResolver("", 0, true, List.of());
                }
                if (current.parameterTypes().isEmpty()) {
                    return new PartitionKeyResolver(expression, 0, true, List.of());
                }
                ParsedPartitionExpression parsed = parse(expression, current.parameterTypes().size());
                List<Accessor> accessors = compileAccessors(
                        current.parameterTypes().get(parsed.argumentIndex()),
                        parsed.path());
                return new PartitionKeyResolver(expression, parsed.argumentIndex(), false, accessors);
            }

            /**
             * 解析分区键。
             *
             * @param args 调用参数；可为空。
             * @return 分区键；不可为空；线程安全。
             */
            String resolve(final Object[] args) {
                if (literal) {
                    return expression;
                }
                Object[] values = args == null ? new Object[0] : args;
                if (argumentIndex >= values.length) {
                    throw contractError("partition key argument index out of bounds: " + argumentIndex, null);
                }
                Object value = values[argumentIndex];
                for (Accessor accessor : accessors) {
                    if (value == null) {
                        return "";
                    }
                    value = accessor.read(value);
                }
                return value == null ? "" : String.valueOf(value);
            }

            private static ParsedPartitionExpression parse(final String expression, final int parameterCount) {
                if (!hasArgumentPrefix(expression)) {
                    return new ParsedPartitionExpression(0, expression);
                }
                int dot = expression.indexOf('.');
                String indexText = dot < 0 ? expression.substring(3) : expression.substring(3, dot);
                int index = parseArgumentIndex(indexText);
                if (index < 0 || index >= parameterCount) {
                    throw contractError("partition key argument index out of bounds: " + index, null);
                }
                return new ParsedPartitionExpression(index, dot < 0 ? "" : expression.substring(dot + 1));
            }

            private static boolean hasArgumentPrefix(final String expression) {
                if (!expression.startsWith("arg")) {
                    return false;
                }
                if (expression.length() == 3) {
                    return true;
                }
                char next = expression.charAt(3);
                return next == '.' || Character.isDigit(next);
            }

            private static int parseArgumentIndex(final String indexText) {
                try {
                    return indexText.isBlank() ? 0 : Integer.parseInt(indexText);
                } catch (NumberFormatException ex) {
                    throw contractError("partition key argument index is invalid: " + indexText, ex);
                }
            }

            private static List<Accessor> compileAccessors(final Class<?> rootType, final String path) {
                if (path.isBlank()) {
                    return List.of();
                }
                String[] segments = path.split("\\.");
                List<Accessor> result = new ArrayList<>(segments.length);
                Class<?> currentType = rootType;
                for (String segment : segments) {
                    if (segment.isBlank()) {
                        throw contractError("partition key path segment must not be blank: " + path, null);
                    }
                    Accessor accessor = Accessor.compile(currentType, segment);
                    result.add(accessor);
                    currentType = accessor.valueType();
                }
                return result;
            }

            private static ZeroException contractError(final String message, final Throwable cause) {
                return ZeroException.of(RpcErrorCode.CONTRACT_INVALID, message, cause);
            }
        }

        /**
         * 已缓存的分区键访问器。
         *
         * @param name 访问器名称。
         * @param valueType 访问值类型。
         * @param handle 访问句柄。
         */
        private record Accessor(String name, Class<?> valueType, MethodHandle handle) {

            /**
             * 创建访问器。
             */
            private Accessor {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(valueType, "valueType");
                Objects.requireNonNull(handle, "handle");
            }

            /**
             * 编译访问器。
             *
             * @param ownerType 拥有者类型；不可为空。
             * @param name 字段、getter 或 record accessor 名称；不可为空。
             * @return 访问器；不可为空。
             */
            static Accessor compile(final Class<?> ownerType, final String name) {
                Class<?> currentOwner = Objects.requireNonNull(ownerType, "ownerType");
                String currentName = Objects.requireNonNull(name, "name");
                Field field = findField(currentOwner, currentName);
                if (field != null) {
                    return fieldAccessor(field, currentName);
                }
                Method method = findAccessorMethod(currentOwner, currentName);
                if (method != null) {
                    return methodAccessor(method, currentName);
                }
                throw PartitionKeyResolver.contractError("partition key accessor not found: "
                        + currentOwner.getName() + "#" + currentName, null);
            }

            /**
             * 读取访问器值。
             *
             * @param target 目标对象；不可为空。
             * @return 访问器值；可为空。
             */
            Object read(final Object target) {
                try {
                    return handle.invoke(Objects.requireNonNull(target, "target"));
                } catch (Throwable ex) {
                    throw PartitionKeyResolver.contractError("partition key accessor failed: " + name, ex);
                }
            }

            private static Accessor fieldAccessor(final Field field, final String name) {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                            field.getDeclaringClass(),
                            MethodHandles.lookup());
                    return new Accessor(name, field.getType(), lookup.unreflectGetter(field));
                } catch (IllegalAccessException | SecurityException ex) {
                    throw PartitionKeyResolver.contractError("partition key field is not accessible: " + name, ex);
                }
            }

            private static Accessor methodAccessor(final Method method, final String name) {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                            method.getDeclaringClass(),
                            MethodHandles.lookup());
                    return new Accessor(name, method.getReturnType(), lookup.unreflect(method));
                } catch (IllegalAccessException | SecurityException ex) {
                    throw PartitionKeyResolver.contractError("partition key method is not accessible: " + name, ex);
                }
            }

            private static Field findField(final Class<?> ownerType, final String name) {
                Class<?> current = ownerType;
                while (current != null && !Object.class.equals(current)) {
                    try {
                        return current.getDeclaredField(name);
                    } catch (NoSuchFieldException ex) {
                        current = current.getSuperclass();
                    }
                }
                return null;
            }

            private static Method findAccessorMethod(final Class<?> ownerType, final String name) {
                Method method = findMethod(ownerType, name);
                if (method != null) {
                    return method;
                }
                String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                method = findMethod(ownerType, "get" + capitalized);
                return method == null ? findMethod(ownerType, "is" + capitalized) : method;
            }

            private static Method findMethod(final Class<?> ownerType, final String name) {
                Class<?> current = ownerType;
                while (current != null && !Object.class.equals(current)) {
                    for (Method method : current.getDeclaredMethods()) {
                        if (method.getParameterCount() == 0 && method.getName().equals(name)) {
                            return method;
                        }
                    }
                    current = current.getSuperclass();
                }
                return null;
            }
        }

        /**
         * 已解析的 partitionKey 表达式。
         *
         * @param argumentIndex 参数下标。
         * @param path 访问路径。
         */
        private record ParsedPartitionExpression(int argumentIndex, String path) {

            /**
             * 创建解析结果。
             */
            private ParsedPartitionExpression {
                Objects.requireNonNull(path, "path");
            }
        }
    }
}
