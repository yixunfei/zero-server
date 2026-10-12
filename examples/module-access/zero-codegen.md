# zero-codegen 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-codegen</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

协议 DSL 与脚手架生成。

核心 API：`ProtocolCodegenCli、ProjectScaffoldCli`。

构建配置示例；Parent/BOM/生成器通过 POM 接入，不是 Java 业务对象。

从 [pom.xml](../rpg-tcp-generated/pom.xml) 的 **generate-sources 中 ProtocolCodegenCli 与 build-helper** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml clean test
```

DSL 位于场景 src/main/protocol/RpgTcp.si；编辑 DSL 后重新生成，不手改 target/generated-sources。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
