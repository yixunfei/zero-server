#requires -Version 7.0
[CmdletBinding()]
param([switch]$Plan)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runId = [guid]::NewGuid().ToString('N')
$out = Join-Path $root "target/acceptance-evidence/center-logic-kafka/$runId"
$composeFile = Join-Path $root 'examples/kafka-nacos-external/compose-kafka-only.yaml'
$project = "zero-center-logic-$runId"
$control = Join-Path $out 'control'
$logs = Join-Path $out 'logs'
$manifestPath = Join-Path $out 'manifest.json'
if ($Plan) {
    Write-Output 'center-logic-kafka-plan=isolated-broker+center-jvm+logic-jvm'
    Write-Output "compose=$composeFile"
    Write-Output 'assertions=request-response,register,unregister,late,duplicate,drain,stop,resource-recovery'
    exit 0
}
New-Item -ItemType Directory -Force -Path $logs, $control | Out-Null
$manifest = [ordered]@{
    schema = 'zero-center-logic-kafka-evidence/v3'
    runId = $runId
    project = $project
    status = 'blocked'
    productionReady = $false
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
    assertions = @('independent-jvm','topic-isolated','consumer-group-isolated','instance-metadata','correlationId','traceId','timeoutAt','register','request-response','unregister','drain','stop','resource-recovery')
    logs = @()
}
$center = $null
$logic = $null
$cleanupExit = 0
try {
    $java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $javaVersion = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "2[1-9]') { throw 'Java 21 or newer is required for the two-JVM fixture' }
    $manifest.javaVersion = ($javaVersion -split "`n" | Select-Object -First 1).Trim()
    $dockerVersion = (& docker version --format '{{.Server.Version}}' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon unavailable' }
    $manifest.dockerVersion = $dockerVersion
    $port = 39000 + ([Math]::Abs($runId.GetHashCode()) % 2000)
    $env:ZERO_KAFKA_PORT = $port
    $manifest.requestedKafkaPort = $port
    $mvn = if (Test-Path (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/center/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$out/center-cp.txt"
    if ($LASTEXITCODE -ne 0) { throw 'failed to resolve center runtime classpath' }
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/logic/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$out/logic-cp.txt"
    if ($LASTEXITCODE -ne 0) { throw 'failed to resolve logic runtime classpath' }
    $manifest.commandBuild = "$mvn -f examples/modular-composition/center-logic-kafka/pom.xml clean test"
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/pom.xml') clean test *> (Join-Path $logs 'module-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'center-logic-kafka module build failed' }
    & docker compose -f $composeFile -p $project up -d --wait --wait-timeout 240 *> (Join-Path $logs 'broker.log')
    if ($LASTEXITCODE -ne 0) { throw 'Kafka broker failed to become ready' }
    $manifest.broker = 'healthy'
    $published = (& docker compose -f $composeFile -p $project port kafka 9092).Trim().Split(':')[-1]
    $manifest.bootstrap = "127.0.0.1:$published"
    $prefix = "zero.onboarding.$runId"
    $centerCp = "$(Get-Content (Join-Path $out 'center-cp.txt'))"
    $logicCp = "$(Get-Content (Join-Path $out 'logic-cp.txt'))"
    $centerClasses = "$root/examples/modular-composition/center-logic-kafka/center/target/classes;$root/examples/modular-composition/center-logic-kafka/common-contract/target/classes;$centerCp"
    $logicClasses = "$root/examples/modular-composition/center-logic-kafka/logic/target/classes;$root/examples/modular-composition/center-logic-kafka/common-contract/target/classes;$logicCp"
    $center = Start-Process $java -ArgumentList @('-cp',$centerClasses,'group.zn.zero.examples.centerlogic.kafka.center.CenterMain',$manifest.bootstrap,$prefix,$control) -RedirectStandardOutput (Join-Path $logs 'center.log') -RedirectStandardError (Join-Path $logs 'center-error.log') -PassThru
    $logic = Start-Process $java -ArgumentList @('-cp',$logicClasses,'group.zn.zero.examples.centerlogic.kafka.logic.LogicMain',$manifest.bootstrap,$prefix,$control) -RedirectStandardOutput (Join-Path $logs 'logic.log') -RedirectStandardError (Join-Path $logs 'logic-error.log') -PassThru
    $manifest.centerPid = $center.Id; $manifest.logicPid = $logic.Id
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline -and -not (Test-Path (Join-Path $control 'logic.stopped'))) { Start-Sleep -Milliseconds 250 }
    if (-not (Test-Path (Join-Path $control 'logic.stopped'))) { throw 'logic did not complete lifecycle' }
    New-Item -ItemType File -Force (Join-Path $control 'center.stop') | Out-Null
    $center.WaitForExit(20000); $logic.WaitForExit(20000)
    $manifest.centerExitCode = $center.ExitCode; $manifest.logicExitCode = $logic.ExitCode
    if (-not $center.HasExited -or -not $logic.HasExited -or $center.ExitCode -ne 0 -or $logic.ExitCode -ne 0) { throw 'JVM exit status was not successful' }
    $manifest.status = 'passed'
} catch {
    $manifest.reason = $_.Exception.Message
    try { & docker compose -f $composeFile -p $project logs --no-color --tail 400 *> (Join-Path $logs 'broker-failure.log') } catch { }
} finally {
    if ($null -ne $logic -and -not $logic.HasExited) { try { $logic.Kill(); $logic.WaitForExit(5000) } catch { } }
    if ($null -ne $center -and -not $center.HasExited) { try { $center.Kill(); $center.WaitForExit(5000) } catch { } }
    try { & docker compose -f $composeFile -p $project down --volumes --remove-orphans *> (Join-Path $logs 'cleanup.log'); $cleanupExit = $LASTEXITCODE } catch { $cleanupExit = 1 }
    $manifest.cleanupExitCode = $cleanupExit
    $manifest.cleanupStatus = if ($cleanupExit -eq 0) { 'passed' } else { 'failed' }
    $manifest.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest.logs = @(Get-ChildItem -Path $logs -File | ForEach-Object { "logs/$($_.Name)" })
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
}
Write-Output "center-logic-kafka=$($manifest.status)|runId=$runId|output=$out"
if ($manifest.status -eq 'passed' -and $cleanupExit -eq 0) { exit 0 } else { exit 2 }
