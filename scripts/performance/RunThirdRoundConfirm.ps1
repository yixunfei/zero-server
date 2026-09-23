param([string]$Java = 'D:/env/jdk21/bin/java.exe', [string]$Evidence = 'target/performance-third-20260923')
$ErrorActionPreference = 'Stop'
function Invoke-Measurement {
    param([string]$Library, [string]$Name, [string]$Benchmark, [string[]]$Options,
        [switch]$Scan, [switch]$NoProfiler)
    $forks = if ($Scan) { '2' } else { '3' }
    $iterations = if ($Scan) { '2' } else { '5' }
    $duration = if ($Scan) { '500ms' } else { '1s' }
    $output = "$Evidence/$Name-$Library"
    $arguments = @('-cp', "$Evidence/extra-harness.jar;$Evidence/$Library.jar", 'org.openjdk.jmh.Main', $Benchmark,
        '-foe', 'true', '-f', $forks, '-wi', $iterations, '-i', $iterations, '-w', $duration, '-r', $duration,
        '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC', '-rf', 'json', '-rff', "$output.json") + $Options
    if (-not $NoProfiler) { $arguments += @('-prof', 'gc') }
    ($Java + ' ' + ($arguments -join ' ')) | Set-Content "$output.command.txt"
    & $Java @arguments *> "$output.log"
    if ($LASTEXITCODE -ne 0) { throw "Failed measurement: $output.log" }
}
foreach ($library in @('baseline', 'candidate', 'longadder')) {
    Invoke-Measurement $library 'actor-observation-scan' ActorObservationBenchmark @('-t', '8') -Scan
}
foreach ($threads in @('1', '8', '32')) {
    Invoke-Measurement longadder "actor-$threads-scan" ActorCompletionBenchmark.dispatch @('-p', 'lanes=1,64', '-p', 'completion=sync,async', '-t', $threads) -Scan
    Invoke-Measurement current "outbound-shared-$threads-scan" SharedOutboundBenchmark.send @('-p', 'connections=32', '-p', 'loops=1,4', '-p', 'shared=true,false', '-t', $threads) -Scan
}
foreach ($library in @('baseline', 'current')) {
    Invoke-Measurement $library 'outbound-confirm' NettyOutboundBenchmark.send @('-p', 'count=1,2,32', '-p', 'entry=single')
    Invoke-Measurement $library 'aoi-confirm' AoiSmallChangeBenchmark.round @('-p', 'changes=1,100', '-p', 'type=update,enterLeave', '-p', 'observers=1,16')
    foreach ($threads in @('1', '32')) {
        Invoke-Measurement $library "identity-$threads-confirm" ActorIdentityBenchmark.construct @('-p', 'identity=default,trace', '-t', $threads)
    }
    Invoke-Measurement $library 'event-confirm' EventBusBenchmark.publish @('-p', 'handlers=1,8', '-p', 'completion=sync', '-p', 'consumption=join,ignore,when')
    Invoke-Measurement $library 'outbound-cpu' NettyOutboundBenchmark.send @('-p', 'count=1,2,32', '-p', 'entry=single') -NoProfiler
    Invoke-Measurement $library 'aoi-cpu' AoiSmallChangeBenchmark.round @('-p', 'changes=1,100', '-p', 'type=update', '-p', 'observers=1') -NoProfiler
}
