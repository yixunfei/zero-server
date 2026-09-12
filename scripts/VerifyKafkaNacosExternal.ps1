#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Plan,
    [switch]$Resilience
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $root 'examples/kafka-nacos-external/compose.yaml'
$runId = [guid]::NewGuid().ToString('N')
$project = "zero-kafka-nacos-$runId"
$out = Join-Path $root "target/kafka-nacos-external/$runId"
$compose = @('compose', '-f', $composeFile, '-p', $project)

if ($Plan) {
    Write-Output 'kafka-nacos-external-plan=kafka:3.9.1,nacos:3.2.2'
    Write-Output "compose=$composeFile"
    Write-Output 'ports=127.0.0.1:random|kafka=9092|nacos-http=8848|nacos-grpc=9848'
    Write-Output "resilience=$($Resilience.IsPresent)|fault=graceful-stop-start"
    Write-Output 'cleanup=only-this-compose-project|containers=true|volumes=true|networks=true'
    Write-Output 'tests=KafkaRpcAdapterExternalIT,NacosDiscoveryAdapterExternalIT,KafkaNacosRemoteActorGatewayExternalIT'
    return
}

New-Item -ItemType Directory -Path $out -Force | Out-Null

$envKeys = @(
    'ZERO_KAFKA_BOOTSTRAP_SERVERS', 'ZERO_NACOS_SERVER_ADDR',
    'ZERO_KAFKA_PORT', 'ZERO_NACOS_HTTP_PORT', 'ZERO_NACOS_GRPC_PORT'
)
$old = @{}
foreach ($key in $envKeys) { $old[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }

function Invoke-Native {
    param([string]$Exe, [string[]]$CmdArgs, [string]$Log)
    $text = & $Exe @CmdArgs 2>&1 | Out-String
    $code = $LASTEXITCODE
    if ($Log) { $text | Set-Content -LiteralPath (Join-Path $out $Log) -Encoding utf8 }
    Write-Host $text
    if ($code -ne 0) { throw "$Exe $($CmdArgs -join ' ') failed with exit $code; log=$Log" }
    return $text
}

function Get-PublishedPort {
    param([string]$Service, [int]$ContainerPort)
    $text = (& docker @compose port $Service $ContainerPort 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($text)) {
        throw "cannot resolve published port for $Service/$ContainerPort"
    }
    $first = ($text -split "`r?`n")[0].Trim()
    if (-not $first.StartsWith('127.0.0.1:')) { throw "unexpected published address: $first" }
    return ([uri]("tcp://$first")).Port
}

function Wait-Tcp {
    param([string]$HostName, [int]$Port, [int]$TimeoutSeconds = 180)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $task = $client.ConnectAsync($HostName, $Port)
            if ($task.Wait(1000) -and $client.Connected) { return }
        } catch { }
        finally { $client.Dispose() }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "TCP endpoint not ready: $HostName`:$Port"
}

function New-IsolatedPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

$failure = $null
$kafkaBootstrap = $null
$nacosAddr = $null
try {
    Invoke-Native 'docker' @('version') 'docker.log' | Out-Null
    $env:ZERO_KAFKA_PORT = New-IsolatedPort
    $env:ZERO_NACOS_HTTP_PORT = New-IsolatedPort
    $env:ZERO_NACOS_GRPC_PORT = [int]$env:ZERO_NACOS_HTTP_PORT + 1000

    Invoke-Native 'docker' ($compose + @('up', '-d', '--wait', '--wait-timeout', '240')) 'containers.log' | Out-Null
    Invoke-Native 'docker' ($compose + @('images', '--format', 'json')) 'images.json.log' | Out-Null
    Start-Sleep -Seconds 45

    $kafkaBootstrap = "127.0.0.1:$(Get-PublishedPort 'kafka' 9092)"
    $nacosAddr = "127.0.0.1:$(Get-PublishedPort 'nacos' 8848)"
    Wait-Tcp '127.0.0.1' ([int](Get-PublishedPort 'kafka' 9092))
    Wait-Tcp '127.0.0.1' ([int](Get-PublishedPort 'nacos' 8848))
    Wait-Tcp '127.0.0.1' ([int](Get-PublishedPort 'nacos' 9848))
    $env:ZERO_KAFKA_BOOTSTRAP_SERVERS = $kafkaBootstrap
    $env:ZERO_NACOS_SERVER_ADDR = $nacosAddr

    $common = @(
        '-B', '-ntp', '-q', '-Dzero.external.tests=true',
        "-Dzero.kafka.bootstrapServers=$kafkaBootstrap",
        "-Dzero.kafka.bootstrap=$kafkaBootstrap",
        "-Dzero.nacos.serverAddr=$nacosAddr"
    )

    Invoke-Native 'mvn' ($common + @('-pl', 'zero-rpc-kafka', '-am', '-Pexternal-tests', 'verify')) 'kafka.log' | Out-Null
    Invoke-Native 'mvn' ($common + @('-pl', 'zero-discovery-nacos', '-am', '-Pexternal-tests', 'verify')) 'nacos.log' | Out-Null
    Invoke-Native 'mvn' ($common + @('-pl', 'zero-rpc-kafka', '-am', '-Pexternal-tests', 'verify')) 'kafka-nacos-gateway.log' | Out-Null

    if ($Resilience) {
        Invoke-Native 'docker' ($compose + @('restart', 'kafka')) 'kafka-restart.log' | Out-Null
        Invoke-Native 'docker' ($compose + @('up', '-d', '--wait', '--wait-timeout', '240')) 'kafka-recovered.log' | Out-Null
        Invoke-Native 'docker' ($compose + @('restart', 'nacos')) 'nacos-restart.log' | Out-Null
        Invoke-Native 'docker' ($compose + @('up', '-d', '--wait', '--wait-timeout', '240')) 'nacos-recovered.log' | Out-Null
        Invoke-Native 'mvn' ($common + @('-pl', 'zero-rpc-kafka', '-am', '-Pexternal-tests', 'verify')) 'kafka-recovery.log' | Out-Null
        Invoke-Native 'mvn' ($common + @('-pl', 'zero-discovery-nacos', '-am', '-Pexternal-tests', 'verify')) 'nacos-recovery.log' | Out-Null
    }
} catch {
    $failure = $_
    try { Invoke-Native 'docker' ($compose + @('logs', '--no-color', '--tail', '400')) 'failure-containers.log' | Out-Null } catch { Write-Warning 'cannot collect container logs' }
} finally {
    try { Invoke-Native 'docker' ($compose + @('down', '--volumes', '--remove-orphans')) 'cleanup.log' | Out-Null }
    catch { if ($null -eq $failure) { $failure = $_ } }
    foreach ($key in $envKeys) { [Environment]::SetEnvironmentVariable($key, $old[$key], 'Process') }
}

if ($null -ne $failure) { throw $failure }

$remaining = (& docker ps -a --filter "name=$project" --format '{{.Names}}' 2>&1 | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($remaining)) { throw "containers remain for project $project`: $remaining" }
$networks = (& docker network ls --filter "name=$project" --format '{{.Name}}' 2>&1 | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($networks)) { throw "networks remain for project $project`: $networks" }

@{ kafka = $kafkaBootstrap; nacos = $nacosAddr; project = $project } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'endpoints.json') -Encoding utf8
Write-Output "kafka-nacos-external=ok|crossProcessJvm=true|multiNode=false|resilience=$($Resilience.IsPresent)|containersRemoved=true|networksRemoved=true|outputDir=$out"
