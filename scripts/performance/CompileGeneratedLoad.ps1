param(
    [string]$Jdk = 'D:/env/jdk21',
    [string]$Evidence = 'target/performance-third-20260923',
    [string]$Library = 'current'
)
# 真正运行生成器；生成物与 fixture 只编译到显式 evidence 目录。
$ErrorActionPreference = 'Stop'
$output = "$Evidence/load-generated"
$classes = "$Evidence/load-classes"
& "$Jdk/bin/java.exe" -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar --input scripts/performance/load-fixtures --out $output --pkg group.zn.zero.benchmark.loadgenerated --languages java
if ($LASTEXITCODE -ne 0) { throw 'Load codegen failed' }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = @(Get-ChildItem $output -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
$sources += 'scripts/performance/GeneratedLoadPayload.java'
$sources += 'scripts/performance/ConnectionBurstProbe.java'
$driverRoot = 'zero-benchmarks/src/main/java/group/zn/zero/benchmark/performance'
foreach ($name in @('LoadPayload', 'LoadTls', 'LoadResources', 'LatencyHistogram', 'GameResourceChurn', 'TargetRateEchoServer', 'TargetRateTcpLoad')) {
    $sources += "$driverRoot/$name.java"
}
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) "$Evidence/load-sources.args"), $sources)
& "$Jdk/bin/javac.exe" -proc:none -encoding UTF-8 -cp "$Evidence/$Library.jar" -d $classes "@$Evidence/load-sources.args"
if ($LASTEXITCODE -ne 0) { throw 'Generated load compile failed' }
