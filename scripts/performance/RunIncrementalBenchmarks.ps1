param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$Evidence = 'target/performance-incremental-20260923'
)
# 单个串行 JMH 进程；固定库 jar 与独立 harness，禁止与 Maven/profiler 同时运行。
$ErrorActionPreference = 'Stop'
function Invoke-Sample {
    param([string]$Library, [string]$Name, [string]$Benchmark, [string[]]$Options)
    $arguments = @('-cp', "$Evidence/benchmark-harness.jar;$Evidence/$Library.jar", 'org.openjdk.jmh.Main',
        $Benchmark, '-foe', 'true', '-f', '2', '-wi', '2', '-i', '3', '-w', '500ms', '-r', '500ms', '-prof', 'gc',
        '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC', '-rf', 'json', '-rff', "$Evidence/$Name.json") + $Options
    & $Java @arguments *> "$Evidence/$Name.log"
    if ($LASTEXITCODE -ne 0) { throw "JMH failed: $Name; see $Evidence/$Name.log" }
}
Invoke-Sample current event-after EventBusBenchmark.publish @('-p', 'handlers=0,1,8', '-p', 'completion=sync,completed,async', '-t', '1')
Invoke-Sample current aoi-after AoiObserverBenchmark.round @('-p', 'size=2000', '-p', 'observers=1,16', '-p', 'updates=0,1,100')
Invoke-Sample current ranking-after SharedRankingBenchmark.mixed @('-p', 'size=10000', '-p', 'boards=1,8', '-p', 'writes=0,10,100', '-t', '8')
foreach ($library in @('baseline', 'current')) {
    Invoke-Sample $library "protocol-$library" ProtocolBufferBenchmark.nested @('-p', 'size=64,32768', '-p', 'depth=1,8', '-p', 'backend=heap,direct,nettyDirect')
    Invoke-Sample $library "reader-$library" ProtocolBufferBenchmark.readView @('-p', 'size=64,1024,32768', '-p', 'depth=1', '-p', 'backend=heap')
    Invoke-Sample $library "actor-$library" ActorCompletionBenchmark.dispatch @('-p', 'lanes=1,64', '-p', 'completion=sync,async,rejected', '-t', '8')
    Invoke-Sample $library "event-shared-$library" EventBusBenchmark.publish @('-p', 'handlers=1,8', '-p', 'completion=sync,async', '-t', '8')
}
