# 生产配置诊断与基础生命周期

覆盖模块：zero-runtime-production、zero-server-starter-production。依赖见 [pom.xml](pom.xml)，入口见 [ProductionExample.java](src/main/java/group/zn/zero/examples/recipes/production/ProductionExample.java) 的 `runFlow()`，测试见 [ProductionExampleTest.java](src/test/java/group/zn/zero/examples/recipes/production/ProductionExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/production/pom.xml test exec:java
```

## 接入步骤

1. 启用 Redis cache 并提供仅作诊断的 fixture URI，故意不提供 namespace、cache-name 和 codec。
2. 使用生产 Starter 的 `diagnose()` 查看 `MISSING_CONFIG` 和缺失键，检查输出不含 URI/密码。
3. `build()` 应返回 `CONFIG_MISSING`，保留其他异常，不将其当成预期结果。
4. 另用 `ProductionAssembly` 构建未安装外部 Adapter 的基础运行时，启动后检查受管 IO，最后关闭。

## 预期结果

redisState=MISSING_CONFIG，缺 builder.redisCacheValueCodec、zero.adapter.cache.redis.cache-name、zero.adapter.cache.redis.namespace；failureCode=ZERO-PRODUCTION-ADAPTER-CONFIG-MISSING；redacted/started/stopped/managedIo=true。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

两处组合器均使用 `configSourceLookups(key -> null, key -> null)`，确保示例不受本机环境变量/JVM 属性影响。真实部署应改为明确的配置来源并管理凭证。

诊断不创建真实 Adapter，不验证服务可达或健康。显式传入的执行器由示例在诊断失败路径兜底关闭；成功 build 后 runtime 拥有其资源。本例运行的基础组合根没有安装外部模块，不能据此宣称 Redis/Kafka/Nacos 已就绪。

生产示例依赖包含真实驱动的 Starter，以复用实际配置诊断。低依赖应用可只使用 `zero-runtime-production` 的 `ProductionAssembly` 并按需安装 Adapter；真实接入还需配置 codec、命名空间、业务模型与启动健康检查。

## 扩展位置

外部存储接入参考 [Repository 组合](../../repository-composition/README.md)，Kafka 业务通信参考 [双进程示例](../../modular-composition/center-logic-kafka/README.md)。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
