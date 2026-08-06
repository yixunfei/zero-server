# CSV 配置热重载片段

将 `items.csv` 放入项目部署配置目录，把两个 Java 文件的 `{{packageName}}` 替换为业务包名。

在 runtime 构建阶段显式装配：

```java
InMemoryLogSink terminalLogSink = new InMemoryLogSink();
ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("{{artifactId}}", 4);
ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, terminalLogSink, executors);
LocalConfigHotReloadService configService = ZeroConfigHotReloadFactory
        .configure(builder, config, builder.logAppender(), executors)
        .orElseThrow();
GameItemConfigModule itemConfigs = GameItemConfigModule.register(configService, configDirectory);
ZeroRuntimeComponents components = builder.build();
components.start();
```

最小配置：

```properties
zero.config.hot-reload.enabled=true
zero.config.hot-reload.watch-enabled=false
zero.config.hot-reload.debounce-ms=500
zero.config.hot-reload.stop-timeout-ms=3000
zero.config.hot-reload.operator=local-config
```

业务读取使用 `itemConfigs.items().find(id)` 或先保存一次 `snapshot()` 完成本次逻辑。若需要响应配置变化，只能向 player/scene Actor 投递消息，不得在 reload observer 中跨线程直接修改 Actor 状态。
