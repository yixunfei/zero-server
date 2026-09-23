param(
    [string]$Java = 'D:/env/jdk21/bin/java.exe',
    [string]$Evidence = 'target/performance-third-20260923',
    [string]$Library = 'baseline',
    [ValidateSet('outbound', 'aoi', 'actor', 'identity', 'event', 'bridge')][string]$Suite = 'outbound',
    [switch]$Confirm,
    [switch]$NoProfiler
)
# 独占使用 JMH；不要同时启动其他 JMH、Maven 或 profiler。
$ErrorActionPreference = 'Stop'
function Invoke-Sample {
    param([string]$Name, [string]$Benchmark, [string[]]$Options)
    $forks = if ($Confirm) { '3' } else { '2' }
    $iterations = if ($Confirm) { '5' } else { '2' }
    $duration = if ($Confirm) { '1s' } else { '500ms' }
    $suffix = if ($Confirm) { 'confirm' } else { 'scan' }
    if ($NoProfiler) { $suffix += '-cpu' }
    $output = "$Evidence/$Name-$Library-$suffix"
    $arguments = @('-jar', "$Evidence/$Library.jar", $Benchmark, '-foe', 'true',
        '-f', $forks, '-wi', $iterations, '-i', $iterations, '-w', $duration, '-r', $duration,
        '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC', '-rf', 'json', '-rff', "$output.json") + $Options
    if (-not $NoProfiler) { $arguments += @('-prof', 'gc') }
    ($Java + ' ' + ($arguments -join ' ')) | Set-Content "$output.command.txt"
    & $Java @arguments *> "$output.log"
    if ($LASTEXITCODE -ne 0) { throw "JMH failed: $output.log" }
}
switch ($Suite) {
    'outbound' { Invoke-Sample 'outbound' NettyOutboundBenchmark.send @('-p', 'count=0,1,2,8,32', '-p', 'entry=single,batch') }
    'aoi' { Invoke-Sample 'aoi-small' AoiSmallChangeBenchmark.round @('-p', 'changes=1,2,100', '-p', 'type=update,enterLeave,mixed', '-p', 'observers=1,16') }
    'actor' {
        foreach ($threads in @('1', '8', '32')) {
            Invoke-Sample "actor-$threads" ActorCompletionBenchmark.dispatch @('-p', 'lanes=1,64', '-p', 'completion=sync,async', '-t', $threads)
        }
    }
    'identity' {
        foreach ($threads in @('1', '8', '32')) {
            Invoke-Sample "identity-$threads" ActorIdentityBenchmark.construct @('-t', $threads)
        }
    }
    'event' { Invoke-Sample 'event' EventBusBenchmark.publish @('-p', 'handlers=0,1,8', '-p', 'completion=sync', '-p', 'consumption=join,ignore,then,when') }
    'bridge' { Invoke-Sample 'bridge' NettyFramePathBenchmark @('-p', 'size=64,1024,32768,131072') }
}
