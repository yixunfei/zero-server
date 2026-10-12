# 本地受管定时任务

该示例展示 `zero-server-starter` 如何统一管理 timer、worker、remote IO 和 Actor 执行域。示例覆盖 once、fixed-delay、fixed-rate 跳过策略、失败策略、取消、TraceId 传递和停止时资源回收，不连接外部服务。

在仓库根目录使用 JDK 21 和 Maven Wrapper 运行；首次先安装当前框架依赖：

```powershell
./mvnw.cmd -B -ntp -DskipTests install
./mvnw.cmd -B -ntp -f examples/managed-scheduler-local/pom.xml clean test exec:java
```

从 [ManagedSchedulerLocalApplication.java](src/main/java/group/zn/zero/examples/scheduler/ManagedSchedulerLocalApplication.java) 复制完整装配流程。只需要定时任务契约时依赖 `zero-core`；需要本地受管 timer 时选择 Starter，并通过配置启用，不要在业务代码中自行创建线程池。

周期任务和异步任务都应有界、可取消并可观测。示例的等待预算、短周期和本地执行器只用于 smoke 验收，不代表生产容量或 SLA。
