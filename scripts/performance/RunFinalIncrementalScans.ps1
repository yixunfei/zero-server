param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$Evidence = 'target/performance-incremental-20260923'
)
$ErrorActionPreference = 'Stop'
function Invoke-FinalSample {
    param([string]$Library, [string]$Name, [string]$Benchmark, [string[]]$Options)
    $arguments = @('-jar', "$Evidence/$Library.jar", $Benchmark, '-foe', 'true', '-f', '2', '-wi', '2', '-i', '3',
        '-w', '500ms', '-r', '500ms', '-prof', 'gc', '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC',
        '-rf', 'json', '-rff', "$Evidence/$Name.json") + $Options
    & $Java @arguments *> "$Evidence/$Name.log"
    if ($LASTEXITCODE -ne 0) { throw "JMH failed: $Name" }
}
# 最终默认实现与早期测量存在差异的路径，以及明确的负样本。
Invoke-FinalSample final event-final EventBusBenchmark.publish @('-p', 'handlers=0,1,8', '-p', 'completion=sync,completed,async', '-t', '1')
Invoke-FinalSample final netty-final ProtocolBufferBenchmark.nested @('-p', 'backend=nettyDirect', '-p', 'depth=1,8', '-p', 'size=64,32768')
Invoke-FinalSample final reader-final ProtocolBufferBenchmark.readView @('-p', 'backend=heap', '-p', 'depth=1', '-p', 'size=64,1024,32768')
Invoke-FinalSample final utf8-experiment 'Utf8BackendExperiment.*' @()
foreach ($library in @('baseline', 'final')) {
    Invoke-FinalSample $library "aoi-churn-$library" AoiObserverBenchmark.churn @('-p', 'observers=1', '-p', 'updates=0', '-p', 'size=100,2000')
    Invoke-FinalSample $library "event-failure-$library" EventBusBenchmark.publish @('-p', 'handlers=1,8', '-p', 'completion=failure', '-t', '1')
    Invoke-FinalSample $library "registration-$library" EventBusBenchmark.registration @('-p', 'handlers=8', '-p', 'completion=sync', '-t', '8')
    & $Java '-Xms512m' '-Xmx512m' '-XX:+UseG1GC' -cp "$Evidence/$library.jar" scripts/performance/AoiRetentionProbe.java *> "$Evidence/aoi-retention-$library.json"
    if ($LASTEXITCODE -ne 0) { throw "AOI retention probe failed: $library" }
}
