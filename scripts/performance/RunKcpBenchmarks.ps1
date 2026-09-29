param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo 'tasks/kcp-benchmarks' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$previous = Get-Location
try {
    Set-Location -LiteralPath $repo
    [Environment]::OSVersion.VersionString | Set-Content (Join-Path $OutputDirectory 'environment.txt')
    & .\mvnw.cmd -version 2>&1 | Add-Content (Join-Path $OutputDirectory 'environment.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Maven/JDK; set JAVA_HOME to a Java 21 installation.' }
    & git rev-parse HEAD | Add-Content (Join-Path $OutputDirectory 'environment.txt')
    & git status --short | Add-Content (Join-Path $OutputDirectory 'environment.txt')
    # 单独 JVM 运行对照负载，避免其他测试预热污染同机前后比较。
    & .\mvnw.cmd -pl zero-net-kcp -am '-Dtest=KcpWorkloadTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
        Tee-Object -FilePath (Join-Path $OutputDirectory 'workload.log')
    if ($LASTEXITCODE -ne 0) { throw 'KCP workload verification failed; inspect workload.log' }
    & .\mvnw.cmd -pl zero-net-kcp -am '-Dtest=KcpOptimizationTest,KcpFaultMatrixTest,KcpCapacityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
        Tee-Object -FilePath (Join-Path $OutputDirectory 'faults-allocation-capacity.log')
    if ($LASTEXITCODE -ne 0) { throw 'KCP fault/capacity verification failed; inspect faults-allocation-capacity.log' }
    Write-Output 'Loopback/virtual-network results are not a production capacity guarantee.'
} finally { Set-Location -LiteralPath $previous }
