# zero-rpc-kafka 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-rpc-kafka</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

Kafka RPC envelope 和超时轮。

核心 API：`KafkaRpcAdapter、KafkaRpcSettings`。

外部组件入口；命令的实际覆盖范围及环境前置条件见下方说明。

从 [CenterMain.java](../../examples/modular-composition/center-logic-kafka/center/src/main/java/group/zn/zero/examples/centerlogic/kafka/center/CenterMain.java) 的 **main；配合 logic 子模块的 LogicMain** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/modular-composition/center-logic-kafka/pom.xml clean install
```

本命令只构建共享契约与角色，不启动 Kafka，也不验证双 JVM RPC；真实运行按场景 README 和 scripts/VerifyCenterLogicKafka.ps1。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-rpc-kafka.md)。
