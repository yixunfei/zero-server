param([string]$Jdk = 'D:/env/jdk21', [string]$Evidence = 'target/performance-third-20260923', [switch]$HarnessOnly)
# 独立实验目录，不改 Maven 产物，不修改生产源码；与构建和测量串行执行。
$ErrorActionPreference = 'Stop'
$processor = "$env:USERPROFILE/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar"
$harnessName = if ($HarnessOnly) { 'extra-harness-final' } else { 'extra-harness' }
$classes = "$Evidence/$harnessName"
New-Item -ItemType Directory -Force $classes | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -cp "$Evidence/baseline.jar" -processorpath "$processor;$Evidence/baseline.jar" `
    -processor org.openjdk.jmh.generators.BenchmarkProcessor -d $classes scripts/performance/SharedOutboundBenchmark.java scripts/performance/ActorObservationBenchmark.java scripts/performance/EventCompositionBenchmark.java
if ($LASTEXITCODE -ne 0) { throw 'Extra harness compilation failed' }
& "$Jdk/bin/jar.exe" cf "$Evidence/$harnessName.jar" -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Extra harness archive failed' }
if ($HarnessOnly) { return }
$source = Get-Content -Raw "$Evidence/ExecutorActorScheduler.baseline.java"
$source = $source.Replace('private final AtomicInteger active = new AtomicInteger();', 'private final LongAdder active = new LongAdder();')
$source = $source.Replace('active.incrementAndGet()', 'active.increment()').Replace('active.decrementAndGet()', 'active.decrement()').Replace('active.get()', 'active.intValue()')
New-Item -ItemType Directory -Force "$Evidence/longadder-src", "$Evidence/longadder-classes" | Out-Null
[IO.File]::WriteAllText((Join-Path (Get-Location) "$Evidence/longadder-src/ExecutorActorScheduler.java"), $source)
& "$Jdk/bin/javac.exe" -proc:none -encoding UTF-8 -cp "$Evidence/baseline.jar" -d "$Evidence/longadder-classes" "$Evidence/longadder-src/ExecutorActorScheduler.java"
if ($LASTEXITCODE -ne 0) { throw 'LongAdder candidate compile failed' }
Copy-Item "$Evidence/baseline.jar" "$Evidence/longadder.jar"
& "$Jdk/bin/jar.exe" uf "$Evidence/longadder.jar" -C "$Evidence/longadder-classes" group
if ($LASTEXITCODE -ne 0) { throw 'LongAdder candidate archive failed' }
