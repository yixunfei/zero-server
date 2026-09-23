param(
    [ValidatePattern('^[A-Za-z0-9-]+$')][string]$RunName = 'tcp-load',
    [int]$Connections = 1000,
    [int]$Rate = 2000,
    [int]$Seconds = 120,
    [int]$PayloadBytes = 1024,
    [string]$ServerJar = 'target/performance-20260923/final.jar',
    [string]$ClientJar = 'target/performance-20260923/final.jar',
    [switch]$Churn,
    [switch]$GameChurn
)
# 独立 JVM 服务/客户端；只写本仓库 target，监控 Windows RSS 与句柄，不修改系统网络参数。
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = Join-Path $repoRoot "target/performance-20260923/$RunName"
if (Test-Path -LiteralPath $outputRoot) { throw "Run directory already exists: $outputRoot" }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$javaPath = 'D:/env/jdk21/bin/java.exe'
$driverClasses = Join-Path $repoRoot 'target/performance-20260923/load-classes'
$serverClasspath = "$driverClasses;$(Join-Path $repoRoot $ServerJar)"
$clientClasspath = "$driverClasses;$(Join-Path $repoRoot $ClientJar)"
$portFile = Join-Path $outputRoot 'port.txt'
$stopFile = Join-Path $outputRoot 'stop'
$metadata = @{ connections = $Connections; rate = $Rate; seconds = $Seconds; payloadBytes = $PayloadBytes;
    churn = $Churn.IsPresent; gameChurn = $GameChurn.IsPresent; serverJar = $ServerJar; clientJar = $ClientJar;
    serverSha256 = (Get-FileHash (Join-Path $repoRoot $ServerJar)).Hash;
    clientSha256 = (Get-FileHash (Join-Path $repoRoot $ClientJar)).Hash;
    jvmArgs = '-Xms256m -Xmx512m -XX:+UseG1GC'; latency = 'planned send to complete response; logarithmic bucket upper bounds';
    startedAt = (Get-Date -Format o);
    drivers = @(Get-ChildItem -LiteralPath $driverClasses -Recurse -Filter '*.class' | ForEach-Object {
        @{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    }) }
$metadata | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $outputRoot 'metadata.json')
$serverProcess = Start-Process -FilePath $javaPath -WindowStyle Hidden -PassThru -WorkingDirectory $repoRoot `
    -ArgumentList @('-Xms256m','-Xmx512m','-XX:+UseG1GC','-cp', ('"' + $serverClasspath + '"'),
        'group.zn.zero.benchmark.performance.TargetRateEchoServer', ('"' + $portFile + '"'), ('"' + $stopFile + '"'),
        $GameChurn.IsPresent.ToString().ToLowerInvariant()) `
    -RedirectStandardOutput (Join-Path $outputRoot 'server.jsonl') -RedirectStandardError (Join-Path $outputRoot 'server.stderr')
try {
    $startupDeadline = (Get-Date).AddSeconds(30)
    while (!(Test-Path -LiteralPath $portFile)) {
        if ($serverProcess.HasExited -or (Get-Date) -gt $startupDeadline) { throw 'Server did not start' }
        Start-Sleep -Milliseconds 200
    }
    $port = (Get-Content -Raw $portFile).Trim()
    $clientProcess = Start-Process -FilePath $javaPath -WindowStyle Hidden -PassThru -WorkingDirectory $repoRoot `
        -ArgumentList @('-Xms256m','-Xmx512m','-XX:+UseG1GC','-cp', ('"' + $clientClasspath + '"'),
            'group.zn.zero.benchmark.performance.TargetRateTcpLoad', $port, $Connections, $Rate, $Seconds, $PayloadBytes,
            $Churn.IsPresent.ToString().ToLowerInvariant()) `
        -RedirectStandardOutput (Join-Path $outputRoot 'client.jsonl') -RedirectStandardError (Join-Path $outputRoot 'client.stderr')
    $deadline = (Get-Date).AddSeconds($Seconds + 120)
    while (!$clientProcess.HasExited) {
        if ((Get-Date) -gt $deadline) { Stop-Process -Id $clientProcess.Id; throw 'Client exceeded bounded run time' }
        foreach ($role in @(@('server', $serverProcess.Id), @('client', $clientProcess.Id))) {
            $currentProcess = Get-Process -Id $role[1] -ErrorAction SilentlyContinue
            if ($null -ne $currentProcess) {
                @{ timeMillis = [DateTimeOffset]::Now.ToUnixTimeMilliseconds(); role = $role[0]; pid = $role[1];
                    rssBytes = $currentProcess.WorkingSet64; privateBytes = $currentProcess.PrivateMemorySize64;
                    handles = $currentProcess.HandleCount; cpuSeconds = $currentProcess.CPU } |
                    ConvertTo-Json -Compress | Add-Content -Encoding utf8 (Join-Path $outputRoot 'os-resources.jsonl')
            }
        }
        Start-Sleep -Seconds 5
    }
    $clientProcess.WaitForExit()
    if ($clientProcess.ExitCode -ne 0) { throw "Client exit code $($clientProcess.ExitCode)" }
    Get-Content (Join-Path $outputRoot 'client.jsonl') | Select-String '"kind":"result"'
} finally {
    Set-Content -LiteralPath $stopFile -Value 'stop'
    if (!$serverProcess.WaitForExit(30000)) { Stop-Process -Id $serverProcess.Id; throw 'Server shutdown timed out' }
    if ($serverProcess.ExitCode -ne 0) { throw "Server exit code $($serverProcess.ExitCode)" }
}
