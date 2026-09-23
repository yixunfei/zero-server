param(
    [string]$Evidence = 'target/performance-third-20260923',
    [string]$Library = 'final',
    [switch]$IncludeSoak,
    [switch]$Resume
)
# 显式本机矩阵，两个 JVM 各 Xmx512m、ActiveProcessorCount=8；与 JMH/Maven 串行。
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/CompileGeneratedLoad.ps1" -Evidence $Evidence -Library $Library
$certificate = "$Evidence/load-tls.p12"
if (!(Test-Path -LiteralPath $certificate)) {
    & 'D:/env/jdk21/bin/keytool.exe' -genkeypair -alias load -keyalg RSA -keysize 2048 -validity 2 `
        -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 `
        -keystore $certificate -storepass zero-benchmark -keypass zero-benchmark *> "$Evidence/tls-certificate.log"
    if ($LASTEXITCODE -ne 0) { throw 'Local TLS fixture generation failed' }
}
function Invoke-Load {
    param([string]$Name, [hashtable]$Settings)
    if ($Resume -and (Test-Path -LiteralPath "$Evidence/$Name/completed.json")) {
        Write-Output "Already completed: $Name"
        return
    }
    $options = @{
        RunName = $Name; EvidenceRoot = $Evidence; DriverClasses = "$Evidence/load-classes";
        ServerJar = "$Evidence/$Library.jar"; ClientJar = "$Evidence/$Library.jar";
        Connections = 256; Rate = 2000; Seconds = 30; PayloadBytes = 1024; WarmupSeconds = 10
    }
    foreach ($key in $Settings.Keys) { $options[$key] = $Settings[$key] }
    & "$PSScriptRoot/RunTcpLoad.ps1" @options
}
foreach ($version in @('baseline', $Library)) {
    foreach ($generated in @($false, $true)) {
        $kind = if ($generated) { 'dto' } else { 'echo' }
        Invoke-Load "chain-$kind-$version" @{ ServerJar = "$Evidence/$version.jar"; Generated = $generated; Connections = 1000; Seconds = 60 }
    }
}
foreach ($gc in @('G1', 'ZGenerational')) {
    foreach ($repeat in 1..2) {
        Invoke-Load "gc-$gc-$repeat" @{ Gc = $gc; Generated = $true; Rate = 20000 }
    }
}
foreach ($flush in @(0, 16, 64, 256)) {
    Invoke-Load "flush-low-$flush" @{ Generated = $true; Rate = 20; Connections = 32; Flush = $flush }
    Invoke-Load "flush-load-$flush" @{ Generated = $true; Rate = 20000; Flush = $flush }
    Invoke-Load "flush-tls-$flush" @{ Generated = $true; Flush = $flush; TlsKeyStore = $certificate }
    Invoke-Load "flush-burst-$flush" @{ Burst = $true; Generated = $true; Flush = $flush; Connections = 1000; WarmupSeconds = 0 }
    Invoke-Load "flush-slow-$flush" @{ Generated = $true; Flush = $flush; Connections = 32; Rate = 4000; Seconds = 15; WarmupSeconds = 0; PayloadBytes = 16384; ReadDelayMillis = 25; ReceiveBufferBytes = 1024 }
}
foreach ($backlog in @(128, 512, 1024)) {
    foreach ($repeat in 1..3) {
        Invoke-Load "burst-$backlog-$repeat" @{ Burst = $true; Backlog = $backlog; Connections = 1000; WarmupSeconds = 0; Generated = $true }
    }
    Invoke-Load "burst-tls-$backlog" @{ Burst = $true; Backlog = $backlog; Connections = 1000; WarmupSeconds = 0; Generated = $true; TlsKeyStore = $certificate }
}
foreach ($gc in @('G1', 'ZGenerational')) {
    Invoke-Load "profile-$gc" @{ Gc = $gc; Generated = $true; Rate = 10000; Jfr = $true; Seconds = 60 }
}
if ($IncludeSoak) {
    Invoke-Load 'soak-two-hours' @{ Generated = $true; Connections = 1000; Seconds = 7200; WarmupSeconds = 30; Churn = $true; GameChurn = $true; Jfr = $true }
}
