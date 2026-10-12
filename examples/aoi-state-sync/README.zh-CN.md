# AOI 与状态同步基线

本例直接使用 zero-aoi 和 zero-state-sync，在同一个场景 owner 上顺序执行：建立实体 → ENTER → 版本 2 快照 → 实体 UPDATE → 版本 3 增量 → 错基线请求 → 全量恢复 → 忽略重复信封。

仓库根目录使用 JDK 21、Maven 3.9+，首次先安装框架依赖：

```powershell
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/aoi-state-sync/pom.xml clean test exec:java
```

成功摘要：

```text
aoi-state-sync=ok|mode=local|sceneSeq=3|visible=1|delta=APPLIED|stale=RESYNC_REQUIRED|recovery=APPLIED|duplicate=IGNORED
```

从 [AoiStateSyncDemo.runFlow](src/main/java/group/zn/zero/examples/aoisync/AoiStateSyncDemo.java) 接入。AOI 的 visible 返回实体 ID；示例用场景实体表解析 ID，将实体投影为 Map/List 基础类型作为同步负载。实体从 hp=100 更新到 hp=95，增量引用更新前基线，恢复快照包含当前完整可见状态。

InMemoryStateSync 校验序号、基线版本和摘要，**不负责应用业务 payload**。APPLIED 只代表信封被接收；客户端实体表的增删改、网络传输和失败恢复由业务实现。示例在结束时释放 AOI 观察者与同步基线。

所有本地索引和同步器都由同一调用线程访问，无外部资源。不要把它们跨线程共享；真实场景应由 scene Actor 串行管理。[测试](src/test/java/group/zn/zero/examples/aoisync/AoiStateSyncDemoTest.java) 核对实体内容、版本关系和恢复行为。