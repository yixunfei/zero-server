param([string]$RepositoryRoot = (Split-Path $PSScriptRoot -Parent))
# 只解析当前 reactor 的精确构件；调用方须先构建，不按时间戳选择历史包。
$ErrorActionPreference = 'Stop'
[xml]$model = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'pom.xml') -Raw -Encoding UTF8
$version = [string]$model.project.version
if ($version -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw 'Cannot resolve literal reactor project.version' }
$jar = Join-Path $RepositoryRoot "zero-codegen/target/zero-codegen-$version-all.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Missing current codegen artifact: $jar. Build with the repository Maven wrapper first."
}
(Resolve-Path -LiteralPath $jar).Path
