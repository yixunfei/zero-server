param([string]$Java = 'D:/env/jdk21/bin/java.exe', [string]$Evidence = 'target/performance-third-20260923',
    [string[]]$Libraries = @('baseline', 'final'))
# 与 Maven/JMH/负载串行；最终调度器已恢复原实现，隔离发布结果变化。
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath "$Evidence/extra-harness-final.jar")) {
    & "$PSScriptRoot/BuildThirdRoundExperiments.ps1" -Evidence $Evidence -HarnessOnly
}
foreach ($library in $Libraries) {
    $output = "$Evidence/event-composition-$library"
    $arguments = @('-cp', "$Evidence/extra-harness-final.jar;$Evidence/$library.jar", 'org.openjdk.jmh.Main',
        'EventCompositionBenchmark', '-foe', 'true', '-f', '3', '-wi', '5', '-i', '5', '-w', '1s', '-r', '1s',
        '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC', '-prof', 'gc', '-rf', 'json', '-rff', "$output.json")
    ($Java + ' ' + ($arguments -join ' ')) | Set-Content "$output.command.txt"
    & $Java @arguments *> "$output.log"
    if ($LASTEXITCODE -ne 0) { throw "Composition measurement failed: $output" }
    $output = "$Evidence/event-negative-$library"
    $arguments = @('-jar', "$Evidence/$library.jar", 'EventBusBenchmark.publish',
        '-p', 'handlers=1,8', '-p', 'completion=async,failure', '-p', 'consumption=join',
        '-foe', 'true', '-f', '3', '-wi', '5', '-i', '5', '-w', '1s', '-r', '1s',
        '-jvmArgs', '-Xms512m -Xmx512m -XX:+UseG1GC', '-prof', 'gc', '-rf', 'json', '-rff', "$output.json")
    ($Java + ' ' + ($arguments -join ' ')) | Set-Content "$output.command.txt"
    & $Java @arguments *> "$output.log"
    if ($LASTEXITCODE -ne 0) { throw "Negative event measurement failed: $output" }
    & $Java -Xms512m -Xmx512m -XX:+UseG1GC -cp "$Evidence/$library.jar" "$PSScriptRoot/AoiRetentionProbe.java" *> "$Evidence/aoi-retention-$library.json"
    if ($LASTEXITCODE -ne 0) { throw "AOI retention probe failed: $library" }
}
