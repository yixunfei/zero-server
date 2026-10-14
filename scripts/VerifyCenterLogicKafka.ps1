#requires -Version 7.0
[CmdletBinding()]
param([switch]$Plan, [string]$BootstrapServers)
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
    Write-Output 'center-logic-kafka-plan=center-jvm+logic-jvm; optional BootstrapServers reuses an existing broker'
    Write-Output "compose=$composeFile"
    Write-Output 'assertions=independent-jvm,signed-service-identity,register,heartbeat,unregister,zero-active,successful-exit'
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
    assertions = @('independent-jvm','topic-isolated','consumer-group-isolated','signed-service-identity','register','heartbeat','unregister','zero-active','successful-exit')
    logs = @()
}
$center = $null
$logic = $null
$cleanupExit = 0
$previousSecret = $env:ZERO_CENTER_LOGIC_SECRET
try {
    $env:ZERO_CENTER_LOGIC_SECRET = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $javaVersion = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "2[1-9]') { throw 'Java 21 or newer is required for the two-JVM fixture' }
    $manifest.javaVersion = ($javaVersion -split "`n" | Select-Object -First 1).Trim()
    if (-not $BootstrapServers) {
        $dockerVersion = (& docker version --format '{{.Server.Version}}' 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Docker daemon unavailable' }
        $manifest.dockerVersion = $dockerVersion
        $port = 39000 + ([Math]::Abs($runId.GetHashCode()) % 2000)
        $env:ZERO_KAFKA_PORT = $port
        $manifest.requestedKafkaPort = $port
    }
    $mvn = if (Test-Path (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
    $manifest.commandBuild = "$mvn -f examples/modular-composition/center-logic-kafka/pom.xml clean install"
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/pom.xml') clean install *> (Join-Path $logs 'module-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'center-logic-kafka module build failed' }
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/center/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$out/center-cp.txt"
    if ($LASTEXITCODE -ne 0) { throw 'failed to resolve center runtime classpath' }
    & $mvn -q -f (Join-Path $root 'examples/modular-composition/center-logic-kafka/logic/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$out/logic-cp.txt"
    if ($LASTEXITCODE -ne 0) { throw 'failed to resolve logic runtime classpath' }
    if ($BootstrapServers) {
        $manifest.broker = 'external'
        $manifest.bootstrap = $BootstrapServers
    } else {
        & docker compose -f $composeFile -p $project up -d --wait --wait-timeout 240 *> (Join-Path $logs 'broker.log')
        if ($LASTEXITCODE -ne 0) { throw 'Kafka broker failed to become ready' }
        $manifest.broker = 'healthy'
        $published = (& docker compose -f $composeFile -p $project port kafka 9092).Trim().Split(':')[-1]
        $manifest.bootstrap = "127.0.0.1:$published"
    }
    $prefix = "zero.onboarding.$runId"
    $centerCp = "$(Get-Content (Join-Path $out 'center-cp.txt'))"
    $logicCp = "$(Get-Content (Join-Path $out 'logic-cp.txt'))"
    $centerClasses = "$root/examples/modular-composition/center-logic-kafka/center/target/classes;$root/examples/modular-composition/center-logic-kafka/common-contract/target/classes;$centerCp"
    $logicClasses = "$root/examples/modular-composition/center-logic-kafka/logic/target/classes;$root/examples/modular-composition/center-logic-kafka/common-contract/target/classes;$logicCp"
    $center = Start-Process $java -WindowStyle Hidden -ArgumentList @('-cp',$centerClasses,'group.zn.zero.examples.centerlogic.kafka.center.CenterMain',$manifest.bootstrap,$prefix,$control) -RedirectStandardOutput (Join-Path $logs 'center.log') -RedirectStandardError (Join-Path $logs 'center-error.log') -PassThru
    $readyDeadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path (Join-Path $control 'center.ready'))) {
        if ($center.HasExited -or (Get-Date) -ge $readyDeadline) { throw 'center failed to become ready' }
        Start-Sleep -Milliseconds 100
    }
    $logic = Start-Process $java -WindowStyle Hidden -ArgumentList @('-cp',$logicClasses,'group.zn.zero.examples.centerlogic.kafka.logic.LogicMain',$manifest.bootstrap,$prefix,$control) -RedirectStandardOutput (Join-Path $logs 'logic.log') -RedirectStandardError (Join-Path $logs 'logic-error.log') -PassThru
    $manifest.centerPid = $center.Id; $manifest.logicPid = $logic.Id
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline -and -not (Test-Path (Join-Path $control 'logic.stopped'))) { Start-Sleep -Milliseconds 250 }
    if (-not (Test-Path (Join-Path $control 'logic.stopped'))) { throw 'logic did not complete lifecycle' }
    New-Item -ItemType File -Force (Join-Path $control 'center.stop') | Out-Null
    $null = $center.WaitForExit(20000)
    $null = $logic.WaitForExit(20000)
    $manifest.centerExitCode = $center.ExitCode; $manifest.logicExitCode = $logic.ExitCode
    if (-not $center.HasExited -or -not $logic.HasExited -or $center.ExitCode -ne 0 -or $logic.ExitCode -ne 0) { throw 'JVM exit status was not successful' }
    if ((Get-Content (Join-Path $control 'center.drained') -Raw) -ne 'active=0|requests=3') { throw 'center registration lifecycle mismatch' }
    if ((Get-Content (Join-Path $control 'logic.stopped') -Raw) -ne 'UNREGISTERED|resources=closed') { throw 'logic unregister marker missing' }
    $manifest.status = 'passed'
} catch {
    $manifest.reason = $_.Exception.Message
    if (-not $BootstrapServers) {
        try { & docker compose -f $composeFile -p $project logs --no-color --tail 400 *> (Join-Path $logs 'broker-failure.log') } catch { }
    }
} finally {
    $env:ZERO_CENTER_LOGIC_SECRET = $previousSecret
    if ($null -ne $logic -and -not $logic.HasExited) { try { $logic.Kill(); $logic.WaitForExit(5000) } catch { } }
    if ($null -ne $center -and -not $center.HasExited) { try { $center.Kill(); $center.WaitForExit(5000) } catch { } }
    if (-not $BootstrapServers) {
        try { & docker compose -f $composeFile -p $project down --remove-orphans *> (Join-Path $logs 'cleanup.log'); $cleanupExit = $LASTEXITCODE } catch { $cleanupExit = 1 }
    }
    $manifest.cleanupExitCode = $cleanupExit
    $manifest.cleanupStatus = if ($cleanupExit -eq 0) { 'passed' } else { 'failed' }
    $manifest.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest.logs = @(Get-ChildItem -Path $logs -File | ForEach-Object { "logs/$($_.Name)" })
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
}
Write-Output "center-logic-kafka=$($manifest.status)|runId=$runId|output=$out"
if ($manifest.status -eq 'passed' -and $cleanupExit -eq 0) { exit 0 } else { exit 2 }
