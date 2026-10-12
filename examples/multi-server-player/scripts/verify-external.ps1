<#
.SYNOPSIS
在本机独立 Compose 项目验证四个组件，或仅探测调用者提供的现有环境。
.DESCRIPTION
需要 PowerShell 7、JDK 21、Maven 和 Docker Compose。
默认运行后移除本次容器与网络，保留具名卷；绝不执行 down -v。
-UseExisting 不调用 Docker、不覆盖连接变量。-Keep 保留新建环境以便调试。
部署配置与日志默认位于 M:\docker_space；源仓库只保存模板。
#>
param(
    [switch]$Keep,
    [switch]$UseExisting,
    [string]$DeploymentRoot = 'M:\docker_space\containers'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$exampleRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Probe {
    # compile 保证新 checkout 可运行，不依赖上次遗留的 target/classes。
    Invoke-Checked 'mvn' @('-B', '-ntp', '-f', (Join-Path $exampleRoot 'pom.xml'),
        '-Pexternal-probe', 'compile', 'exec:exec')
}

if ($UseExisting) {
    foreach ($name in @('ZERO_KAFKA_BOOTSTRAP', 'ZERO_NACOS_ADDRESS', 'ZERO_MONGO_URI',
            'ZERO_MONGO_DATABASE', 'ZERO_REDIS_URI')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            throw "-UseExisting requires $name; no service will be started."
        }
    }
    Invoke-Probe
    return
}

# 使用独立项目名，失败清理不会碰到其他任务的容器。
$projectName = 'zero-player-probe-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$deploymentPath = Join-Path ([IO.Path]::GetFullPath($DeploymentRoot)) $projectName
$composePath = Join-Path $deploymentPath 'compose.yaml'
$settings = @{
    ZERO_KAFKA_BOOTSTRAP = '127.0.0.1:19092'
    ZERO_NACOS_ADDRESS = '127.0.0.1:18848'
    ZERO_MONGO_URI = 'mongodb://127.0.0.1:27018'
    ZERO_MONGO_DATABASE = 'zero_multi_server_player'
    ZERO_REDIS_URI = 'redis://127.0.0.1:16379/0'
}
$savedEnvironment = @{}
$attemptedStartup = $false
$probeFailure = $null
try {
    Invoke-Checked 'docker' @('version', '--format', '{{.Server.Version}}')
    Invoke-Checked 'docker' @('compose', 'version')
    foreach ($port in @(19092, 18848, 19848, 27018, 16379)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "Port $port is occupied. Use -UseExisting with explicit isolated endpoints."
        }
    }
    New-Item -ItemType Directory -Path $deploymentPath -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $exampleRoot 'compose.yaml') -Destination $composePath
    @"
# 玩家示例本机环境
项目：$projectName
模板：$composePath
Kafka 127.0.0.1:19092；Nacos HTTP 18848/gRPC 19848；MongoDB 27018；Redis 16379。
用途：loopback 隔离连通探针；测试配置不启用业务认证，不部署到共享环境。
存储：具名卷保留在 Docker 引擎磁盘；不是此目录的 bind mount。部署前需核验引擎物理存储。
停止：docker compose -p $projectName -f "$composePath" down
数据清理由使用者在明确不再需要后单独决定；脚本不删除卷。
"@ | Set-Content -LiteralPath (Join-Path $deploymentPath 'ENVIRONMENT.md') -Encoding utf8NoBOM
    foreach ($name in $settings.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, $settings[$name])
    }
    Invoke-Checked 'docker' @('compose', '-p', $projectName, '-f', $composePath, 'config', '--quiet')
    $attemptedStartup = $true
    Invoke-Checked 'docker' @('compose', '-p', $projectName, '-f', $composePath,
        'up', '-d', '--wait', '--wait-timeout', '180')
    Invoke-Probe
    Add-Content -LiteralPath (Join-Path $deploymentPath 'ENVIRONMENT.md') -Value '探针结果：全部通过。'
} catch {
    $probeFailure = $_
    if (Test-Path -LiteralPath $deploymentPath) {
        Add-Content -LiteralPath (Join-Path $deploymentPath 'ENVIRONMENT.md') -Value '探针结果：失败，请检查本次运行错误与容器日志。'
    }
    throw
} finally {
    try {
        if ($attemptedStartup) {
            try {
                & docker compose -p $projectName -f $composePath logs --no-color 2>&1 |
                    Set-Content -LiteralPath (Join-Path $deploymentPath 'compose.log') -Encoding utf8NoBOM
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning "Could not collect all container logs for $projectName"
                }
            } catch {
                Write-Warning "Log collection failed for $projectName; cleanup will still run."
            }
        }
        if ($attemptedStartup -and -not $Keep) {
            & docker compose -p $projectName -f $composePath down
            if ($LASTEXITCODE -ne 0) {
                if ($null -ne $probeFailure) {
                    Write-Warning "Cleanup failed; original probe error is preserved. Project: $projectName"
                } else {
                    throw "Compose cleanup failed: $projectName"
                }
            }
        }
    } finally {
        foreach ($name in $savedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
        }
        if (Test-Path -LiteralPath $deploymentPath) {
            Write-Host "Environment record: $(Join-Path $deploymentPath 'ENVIRONMENT.md')"
        }
    }
}
