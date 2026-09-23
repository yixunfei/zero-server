# 2026-09-23 依赖升级与候选合并

本次将经过验证的 Dependabot 依赖升级合入 `main`。项目仍处于 `0.x` 开发预览阶段，升级后的完整组合为：

- MongoDB Java Driver `5.9.2`
- Netty `4.2.17.Final`
- Nacos Client `3.2.3`
- Jedis `8.0.0`
- GitHub Actions `actions/setup-java@v6`
- GitHub Actions `actions/upload-artifact@v7`

SpotBugs Maven Plugin `4.10.3.0` 未合入。使用该版本执行完整 `quality,benchmarks,integration-tests` 门禁时，`zero-protocol` 在 `NativeMemoryZeroBuffer` 和 `ZeroUnsafe` 报告 11 个 `UNS_UNSAFE_CALL`；本次保留 `4.9.3.0`，没有通过 suppression 绕过质量门禁。

## 影响与回滚

MongoDB、Netty、Nacos 和 Jedis 的版本声明发生变化，运行时依赖解析会使用上述版本。Actions 版本只影响 CI 执行环境。若升级后出现回归，将相应版本属性或 workflow action 引用恢复到上一版本，并重新运行完整门禁。

合并前增加了 `ZeroManagedSchedulerFactoryTest` 的有界线程退出等待，修复线程池关闭尾部的偶发竞态；生产调度器实现未改变。

## 验证

本机通过：

```text
mvn -B -ntp -Pquality,benchmarks,integration-tests verify
```

58 个 reactor 项目全部成功，包含 Checkstyle、PMD、SpotBugs、Surefire、Failsafe 和 benchmark smoke。远端 CI 仍需对最终候选提交运行并核对。Redis、MongoDB、Nacos 的真实外部服务测试因本机 Docker Desktop backend 启动失败而未执行，不能将本次结果视为外部组件验收。

历史性能报告基于旧依赖组合，不能作为上述升级版本的性能实测证据。
