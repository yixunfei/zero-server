param(
    [ValidatePattern('^[A-Za-z0-9-]+$')][string]$RunName = 'tcp-load',
    [int]$Connections = 1000,
    [int]$Rate = 2000,
    [int]$Seconds = 120,
    [int]$PayloadBytes = 1024,
    [string]$ServerJar = 'target/performance-20260923/final.jar',
    [string]$ClientJar = 'target/performance-20260923/final.jar',
    [switch]$Churn,
    [switch]$GameChurn,
    [string]$EvidenceRoot = 'target/performance-20260923',
    [string]$DriverClasses = '',
    [ValidateSet('G1', 'ZGenerational')][string]$Gc = 'G1',
    [int]$Backlog = 128,
    [int]$Flush = 0,
    [switch]$Generated,
    [switch]$Jfr,
    [int]$WarmupSeconds = 0,
    [switch]$Burst,
    [switch]$WarmBurstClient,
    [string]$TlsKeyStore = '',
    [int]$ReadDelayMillis = 0,
    [int]$ReceiveBufferBytes = 0
)
# 独立 JVM 服务/客户端；只写本仓库 target，监控 Windows RSS 与句柄，不修改系统网络参数。
$ErrorActionPreference = 'Stop'
if ($WarmBurstClient -and !$Burst) { throw 'WarmBurstClient requires Burst' }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = Join-Path $repoRoot "$EvidenceRoot/$RunName"
if (Test-Path -LiteralPath $outputRoot) { throw "Run directory already exists: $outputRoot" }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$javaPath = 'D:/env/jdk21/bin/java.exe'
$serverClasspath = Join-Path $repoRoot $ServerJar
$clientClasspath = Join-Path $repoRoot $ClientJar
if ($DriverClasses) {
    $driverPath = Join-Path $repoRoot $DriverClasses
    $serverClasspath = "$driverPath;$serverClasspath"
    $clientClasspath = "$driverPath;$clientClasspath"
}
$gcArgs = if ($Gc -eq 'G1') { @('-XX:+UseG1GC') } else { @('-XX:+UseZGC', '-XX:+ZGenerational') }
$commonArgs = @('-Xms256m', '-Xmx512m', '-XX:ActiveProcessorCount=8') + $gcArgs
$clientJvmArgs = @('-Xms256m', '-Xmx512m', '-XX:ActiveProcessorCount=8', '-XX:+UseG1GC')
$payloadArgs = @(if ($Generated) { '-Dzero.load.payload=group.zn.zero.benchmark.performance.GeneratedLoadPayload' })
if ($TlsKeyStore) { $payloadArgs += ('-Dzero.load.tls.keyStore="' + (Resolve-Path -LiteralPath $TlsKeyStore).Path + '"') }
$clientOnlyArgs = @("-Dzero.load.readDelayMillis=$ReadDelayMillis", "-Dzero.load.receiveBufferBytes=$ReceiveBufferBytes", "-Dzero.load.warmupSeconds=$WarmupSeconds")
$serverArgs = $commonArgs + $payloadArgs + @("-Dzero.load.backlog=$Backlog", "-Dzero.load.flush=$Flush", '-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=4,filesize=16m')
if ($Jfr) { $serverArgs += @('-XX:StartFlightRecording=filename=server.jfr,settings=profile,maxsize=128m,dumponexit=true', '-XX:NativeMemoryTracking=summary') }
$portFile = Join-Path $outputRoot 'port.txt'
$stopFile = Join-Path $outputRoot 'stop'
$metadata = @{ connections = $Connections; rate = $Rate; seconds = $Seconds; payloadBytes = $PayloadBytes;
    churn = $Churn.IsPresent; gameChurn = $GameChurn.IsPresent; serverJar = $ServerJar; clientJar = $ClientJar;
    serverSha256 = (Get-FileHash (Join-Path $repoRoot $ServerJar)).Hash;
    clientSha256 = (Get-FileHash (Join-Path $repoRoot $ClientJar)).Hash;
    jvmArgs = $serverArgs; clientJvmArgs = $clientJvmArgs;
    latency = 'planned send to complete response; logarithmic bucket upper bounds';
    gc = $Gc; backlog = $Backlog; flush = $Flush; generated = $Generated.IsPresent; warmupSeconds = $WarmupSeconds; burst = $Burst.IsPresent; tls = [bool]$TlsKeyStore;
    warmBurstClient = $WarmBurstClient.IsPresent; driverClasses = $DriverClasses;
    readDelayMillis = $ReadDelayMillis; receiveBufferBytes = $ReceiveBufferBytes; startedAt = (Get-Date -Format o);
    drivers = @(if ($DriverClasses) { Get-ChildItem -LiteralPath $driverPath -Recurse -Filter '*.class' | ForEach-Object {
        @{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    } }) }
$metadata | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $outputRoot 'metadata.json')
$serverProcess = Start-Process -FilePath $javaPath -WindowStyle Hidden -PassThru -WorkingDirectory $outputRoot `
    -ArgumentList ($serverArgs + @('-cp', ('"' + $serverClasspath + '"'),
        'group.zn.zero.benchmark.performance.TargetRateEchoServer', ('"' + $portFile + '"'), ('"' + $stopFile + '"'),
        $GameChurn.IsPresent.ToString().ToLowerInvariant())) `
    -RedirectStandardOutput (Join-Path $outputRoot 'server.jsonl') -RedirectStandardError (Join-Path $outputRoot 'server.stderr')
try {
    $startupDeadline = (Get-Date).AddSeconds(30)
    while (!(Test-Path -LiteralPath $portFile)) {
        if ($serverProcess.HasExited -or (Get-Date) -gt $startupDeadline) { throw 'Server did not start' }
        Start-Sleep -Milliseconds 200
    }
    $port = (Get-Content -Raw $portFile).Trim()
    & 'D:/env/jdk21/bin/jcmd.exe' $serverProcess.Id VM.flags *> (Join-Path $outputRoot 'effective-flags.txt')
    $clientMain = if ($Burst) { 'group.zn.zero.benchmark.performance.ConnectionBurstProbe' } else { 'group.zn.zero.benchmark.performance.TargetRateTcpLoad' }
    if ($WarmBurstClient) { $clientMain = 'group.zn.zero.benchmark.performance.WarmConnectionBurstProbe' }
    if ($Burst) { & netstat.exe -s -p tcp *> (Join-Path $outputRoot 'tcp-os-before.txt') }
    $clientProcess = Start-Process -FilePath $javaPath -WindowStyle Hidden -PassThru -WorkingDirectory $repoRoot `
        -ArgumentList ($clientJvmArgs + $payloadArgs + $clientOnlyArgs + @('-cp', ('"' + $clientClasspath + '"'),
            $clientMain, $port, $Connections, $Rate, $Seconds, $PayloadBytes,
            $Churn.IsPresent.ToString().ToLowerInvariant())) `
        -RedirectStandardOutput (Join-Path $outputRoot 'client.jsonl') -RedirectStandardError (Join-Path $outputRoot 'client.stderr')
    $deadline = (Get-Date).AddSeconds($Seconds + $WarmupSeconds + 120)
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
    if ($Burst) { & netstat.exe -s -p tcp *> (Join-Path $outputRoot 'tcp-os-after.txt') }
    Get-Content (Join-Path $outputRoot 'client.jsonl') | Select-String '"kind":"result"'
} finally {
    if ($Jfr -and !$serverProcess.HasExited) {
        & 'D:/env/jdk21/bin/jcmd.exe' $serverProcess.Id VM.native_memory summary *> (Join-Path $outputRoot 'native-memory.txt')
    }
    Set-Content -LiteralPath $stopFile -Value 'stop'
    if (!$serverProcess.WaitForExit(30000)) { Stop-Process -Id $serverProcess.Id; throw 'Server shutdown timed out' }
    if ($serverProcess.ExitCode -ne 0) { throw "Server exit code $($serverProcess.ExitCode)" }
}
@{ finishedAt = (Get-Date -Format o); clientExitCode = $clientProcess.ExitCode; serverExitCode = $serverProcess.ExitCode } |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $outputRoot 'completed.json')
