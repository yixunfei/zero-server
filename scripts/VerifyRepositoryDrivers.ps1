#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Plan,
    [switch]$Resilience,
    [ValidateRange(1, 86400)][int]$SoakSeconds = 300
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $repositoryRoot 'examples/repository-composition/compose.yaml'
$projectPom = Join-Path $repositoryRoot 'examples/repository-composition/pom.xml'
$runId = [guid]::NewGuid().ToString('N')
$projectName = "zero-repository-$runId"
$outputDirectory = Join-Path $repositoryRoot "target/repository-driver-verify/$runId"
$composeArguments = @('compose', '-f', $composeFile, '-p', $projectName)

if ($Plan) {
    Write-Output 'repository-driver-plan=local,mongo:7.0,postgres:16,redis:7.2'
    Write-Output "compose=$composeFile"
    Write-Output 'ports=127.0.0.1:random-fixed-for-run|database=zero_contract|table=contract_envelopes'
    Write-Output 'cleanup=only-this-compose-project-and-volumes|external-failures=fail-not-skip'
    Write-Output "resilience=$($Resilience.IsPresent)|soakSeconds=$SoakSeconds|workersPerBackend=4|soakBackendsRunConcurrently=true"
    Write-Output 'fault=graceful-stop-start|redisPersistence=aof-fsync-always|unknown-write-outcome=fail-without-retry'
    return
}

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments, [string]$LogName)
    & $Command @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $outputDirectory $LogName) -Append
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit $LASTEXITCODE; log=$LogName" }
}

function Get-ServicePort {
    param([string]$Service, [int]$ContainerPort)
    $address = & docker @composeArguments port $Service $ContainerPort
    if ($LASTEXITCODE -ne 0) { throw "cannot resolve port for $Service" }
    $uri = [uri]("tcp://" + ($address | Select-Object -First 1).Trim())
    if ($uri.Host -ne '127.0.0.1' -or $uri.Port -le 0) { throw "unexpected published address for $Service" }
    return $uri.Port
}

function Get-IsolatedPorts {
    $listeners = @()
    try {
        foreach ($service in @('mongo', 'postgresql', 'redis')) {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $listeners += $listener
            [pscustomobject]@{ Service = $service; Port = $listener.LocalEndpoint.Port }
        }
    } finally {
        foreach ($listener in $listeners) { $listener.Stop() }
    }
}

function Invoke-RepositoryTests {
    param([string]$Name, [string[]]$Arguments)
    $reports = Join-Path $outputDirectory "$Name-reports"
    try {
        Invoke-Checked mvn ($mavenArguments + "-Dzero.repository.report-directory=$reports" + $Arguments) "$Name.log"
    } finally {
        $summary = Join-Path $reports 'failsafe-summary.xml'
        if (Test-Path -LiteralPath $summary) {
            Copy-Item -LiteralPath $summary -Destination (Join-Path $outputDirectory "$Name-summary.xml")
        }
    }
    [xml]$result = Get-Content -LiteralPath $summary -Raw
    $counts = $result.'failsafe-summary'
    if ($null -eq $counts -or $counts.completed -ne '1' -or $counts.errors -ne '0' -or
            $counts.failures -ne '0' -or $counts.skipped -ne '0') {
        throw "unexpected Failsafe result; report=$summary"
    }
}

. (Join-Path $PSScriptRoot 'dev-env.ps1')
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$environmentKeys = @('ZERO_MONGO_URI', 'ZERO_MONGO_DATABASE', 'ZERO_POSTGRESQL_URL',
    'ZERO_POSTGRES_USER', 'ZERO_POSTGRES_PASSWORD', 'ZERO_POSTGRESQL_TABLE', 'ZERO_REDIS_URI',
    'ZERO_REPOSITORY_COMPOSE_PROJECT', 'ZERO_REPOSITORY_COMPOSE_FILE',
    'ZERO_REPOSITORY_MONGO_PORT', 'ZERO_REPOSITORY_POSTGRESQL_PORT', 'ZERO_REPOSITORY_REDIS_PORT')
$previousEnvironment = @{}
foreach ($key in $environmentKeys) { $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$failure = $null
try {
    Invoke-Checked docker @('version') 'docker.log'
    foreach ($binding in @(Get-IsolatedPorts)) {
        [Environment]::SetEnvironmentVariable("ZERO_REPOSITORY_$($binding.Service.ToUpperInvariant())_PORT", $binding.Port, 'Process')
    }
    Invoke-Checked docker ($composeArguments + @('up', '-d', '--wait', '--wait-timeout', '120')) 'containers.log'
    Invoke-Checked docker ($composeArguments + @('images', '--format', 'json')) 'images.json.log'
    $env:ZERO_MONGO_URI = "mongodb://127.0.0.1:$(Get-ServicePort mongo 27017)"
    $env:ZERO_MONGO_DATABASE = 'zero_contract'
    $env:ZERO_POSTGRESQL_URL = "jdbc:postgresql://127.0.0.1:$(Get-ServicePort postgresql 5432)/zero_contract?connectTimeout=2&socketTimeout=2"
    $env:ZERO_POSTGRES_USER = 'zero_contract'
    $env:ZERO_POSTGRES_PASSWORD = 'zero_contract_local'
    $env:ZERO_POSTGRESQL_TABLE = 'contract_envelopes'
    $env:ZERO_REDIS_URI = "redis://127.0.0.1:$(Get-ServicePort redis 6379)/0"
    $mavenArguments = @('-B', '-ntp', '-q', '-f', $projectPom)
    Invoke-Checked mvn ($mavenArguments + @('clean', 'test')) 'local.log'
    foreach ($backend in @('mongo', 'postgresql', 'redis')) {
        Invoke-RepositoryTests $backend @('-Prepository-external', "-Dzero.repository.backend=$backend", 'verify')
    }
    if ($Resilience) {
        $env:ZERO_REPOSITORY_COMPOSE_PROJECT = $projectName
        $env:ZERO_REPOSITORY_COMPOSE_FILE = $composeFile
        $resilienceArguments = @('-Prepository-resilience', "-Dzero.repository.soak-seconds=$SoakSeconds",
            "-Dzero.repository.fork-timeout-seconds=$($SoakSeconds + 600)", 'verify')
        Invoke-RepositoryTests 'resilience' $resilienceArguments
    }
} catch {
    $failure = $_
    try {
        Invoke-Checked docker ($composeArguments + @('logs', '--no-color', '--tail', '200')) 'failure-containers.log' | Out-Null
    } catch { Write-Warning "cannot collect container failure logs; project=$projectName" }
} finally {
    try {
        Invoke-Checked docker ($composeArguments + @('down', '--volumes', '--remove-orphans')) 'cleanup.log'
    } catch {
        if ($null -eq $failure) { $failure = $_ } else { Write-Warning "container cleanup failed; project=$projectName" }
    } finally {
        foreach ($key in $environmentKeys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    }
}
if ($null -ne $failure) { throw $failure }
Write-Output "repository-drivers=ok|backends=4|resilience=$($Resilience.IsPresent)|containersRemoved=true|outputDir=$outputDirectory"
