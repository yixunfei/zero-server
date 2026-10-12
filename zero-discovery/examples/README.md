# zero-discovery 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-discovery</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

中立服务发现。

核心 API：`ServiceDiscovery、InMemoryServiceDiscovery`。

可直接运行的独立应用示例：[接入步骤与常见场景](../../examples/module-recipes/discovery/README.md)。

从 [DiscoveryExample.java](../../examples/module-recipes/discovery/src/main/java/group/zn/zero/examples/recipes/discovery/DiscoveryExample.java) 的 **runFlow()** 开始；[完整 POM](../../examples/module-recipes/discovery/pom.xml) 展示实际组合依赖。单模块坐标用于选择能力，不等于整个场景所需依赖。

本地注册、分组/集群/健康与业务标签选择、健康切换和注销；不创建网络监听，不证明远程可达。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。首次运行或框架源码变化后先执行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/discovery/pom.xml test exec:java
```

`main` 输出不可变结果并退出，测试验证关键状态与拒绝路径。更底层的原契约入口仍可参考 [DiscoveryConsumerTest.java](../../examples/modular-composition/discovery/src/test/java/group/zn/zero/examples/composition/DiscoveryConsumerTest.java)。

线程、状态和资源归属见代码注释与场景 README；业务不自行创建线程池，Actor/事件回调不阻塞等待同 lane。

相关入口：[七类接入示例](../../examples/module-recipes/README.md)、[全部模块卡片](../../examples/module-access/README.md)、[模块图](../../docs/module-map.md)。
