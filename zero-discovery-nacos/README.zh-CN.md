# zero-discovery-nacos 模块说明

`zero-discovery-nacos` 提供 zeroServer 的服务发现抽象、本地内存注册表和 Nacos 3.x Adapter。Nacos 是可选策略，不是框架强制绑定策略；小型项目、单进程原型和本地测试可以继续使用同一套 `ServiceDiscovery` API 的 `local` 模式。

## 1. Maven 引用

业务项目直接引用本模块即可获得本地注册表、Nacos Adapter、统一配置键和工厂入口：

```xml
<dependency>
    <groupId>group.zn.zero</groupId>
    <artifactId>zero-discovery-nacos</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

如果业务项目只需要本地模式，可以先不引用本模块，后续正式抽象拆分时再迁移到独立 discovery API。当前阶段为了保持同一 API，本地注册表仍随本模块提供。

## 2. 推荐配置入口

推荐外部项目使用 `zero-core` 的标准配置加载器读取 properties 文件：

```powershell
java -Dzero.config.file=D:\game\config\zero-server.properties -jar game-server.jar
```

也可以使用环境变量：

```powershell
$env:ZERO_CONFIG_FILE="D:\game\config\zero-server.properties"
```

代码入口：

```java
ZeroConfig config = ZeroConfigLoader.loadStandard();
ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(config);
discovery.start();
```

调用方负责在进程退出时执行：

```java
discovery.stop();
```

## 3. 最小本地配置

```properties
zero.discovery.mode=local
```

该模式创建 `InMemoryServiceDiscovery`，不需要 Nacos，也不创建外部连接。

## 4. Nacos 配置示例

```properties
zero.discovery.mode=nacos
zero.discovery.nacos.server-addr=127.0.0.1:8848
zero.discovery.nacos.namespace=public
zero.discovery.nacos.default-group=DEFAULT_GROUP
zero.discovery.nacos.default-cluster=DEFAULT
zero.discovery.nacos.request-timeout-millis=3000
zero.discovery.nacos.naming-load-cache-at-start=false
zero.discovery.nacos.health-update-mode=reregister
```

敏感字段不要写入仓库：

```properties
zero.discovery.nacos.username=
zero.discovery.nacos.password=
zero.discovery.nacos.access-key=
zero.discovery.nacos.secret-key=
```

生产环境建议由部署系统、密钥系统或环境变量注入这些字段。

## 5. 标准配置键

配置键集中定义在 `NacosDiscoveryConfigKeys`。

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `zero.discovery.mode` | `local` | 服务发现策略，支持 `local`、`nacos` |
| `zero.discovery.nacos.server-addr` | 无 | Nacos 服务端地址，Nacos 模式必填 |
| `zero.discovery.nacos.namespace` | `public` | Nacos namespace，必须显式写清 |
| `zero.discovery.nacos.username` | 空 | Nacos 用户名 |
| `zero.discovery.nacos.password` | 空 | Nacos 密码 |
| `zero.discovery.nacos.access-key` | 空 | Nacos access key |
| `zero.discovery.nacos.secret-key` | 空 | Nacos secret key |
| `zero.discovery.nacos.default-group` | `DEFAULT_GROUP` | 默认服务分组 |
| `zero.discovery.nacos.default-cluster` | `DEFAULT` | 默认集群 |
| `zero.discovery.nacos.request-timeout-millis` | `3000` | Nacos SDK 请求超时 |
| `zero.discovery.nacos.naming-load-cache-at-start` | `false` | 是否启动时加载本地命名缓存 |
| `zero.discovery.nacos.health-update-mode` | `reregister` | 健康更新模式：`local_only`、`reregister`、`fail_fast` |

兼容入口仍保留：

- 系统属性：`zero.nacos.serverAddr`、`zero.nacos.namespace` 等。
- 环境变量：`ZERO_NACOS_SERVER_ADDR`、`ZERO_NACOS_NAMESPACE` 等。

优先级为：`ZeroConfig`、系统属性、环境变量、默认值。

## 6. 代码用法

本地模式：

```java
ZeroConfig config = new MapZeroConfig(Map.of(
        NacosDiscoveryConfigKeys.DISCOVERY_MODE,
        NacosDiscoveryConfigKeys.MODE_LOCAL));

ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(config);
discovery.start();
```

Nacos 模式：

```java
ZeroConfig config = ZeroConfigLoader.loadStandard();
ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(config);

discovery.start();
try {
    discovery.register(new ServiceInstance(
            "logic-service",
            "logic-1",
            "127.0.0.1",
            6200,
            true,
            Map.of("zone", "local")));
} finally {
    discovery.stop();
}
```

订阅最终实例快照：

```java
ServiceSubscription subscription = discovery.subscribe(
        ServiceQuery.of("logic-service").withSubscribe(true),
        event -> {
            List<ServiceInstance> instances = event.instances();
            // 只处理最终快照，不依赖增量 diff。
        });

subscription.close();
```

## 7. Demo 与测试

示例测试位于：

```text
zero-discovery-nacos/src/test/java/group/zn/zero/discovery/nacos/demo/NacosDiscoveryModuleDemoTest.java
```

示例配置位于：

```text
zero-discovery-nacos/src/test/resources/nacos-demo/
```

默认单元测试不依赖真实 Nacos。

真实 Nacos external-tests 需要显式启动本地 Nacos 3.x，并同时暴露：

- `8848`：HTTP。
- `9848`：Nacos Java SDK gRPC。

执行示例：

```powershell
$env:ZERO_NACOS_SERVER_ADDR="127.0.0.1:8848"
mvn -Pexternal-tests -pl zero-discovery-nacos -am verify
```

## 8. 边界说明

- 本模块不改变 `RpcTransport` 请求语义。
- 本模块不负责 starter 正式默认装配，S2C-05 或单独确认后再推进。
- `ServiceDiscovery` 返回和订阅的是最终实例快照，增量 diff API 暂不冻结。
- `NacosDiscoveryFactory` 只负责创建实现，不托管生命周期。
- 业务 listener 不应执行阻塞远程 IO；当前实现只做异常隔离，不创建框架级回调线程池。
