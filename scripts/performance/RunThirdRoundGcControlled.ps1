param(
    [string]$Evidence = 'target/performance-third-20260923',
    [switch]$IncludeChain
)
# 独立服务端 GC 对照：客户端统一 G1。已有两端同时切 GC 的样本只作诊断。
$ErrorActionPreference = 'Stop'
foreach ($repeat in 1..2) {
    foreach ($gc in @('ZGenerational', 'G1')) {
        & "$PSScriptRoot/RunTcpLoad.ps1" -RunName "gc-server-$gc-$repeat" -EvidenceRoot $Evidence `
            -ServerJar "$Evidence/final.jar" -ClientJar "$Evidence/final.jar" `
            -DriverClasses "$Evidence/load-classes" -Generated -Gc $gc `
            -Connections 256 -Rate 20000 -Seconds 30 -PayloadBytes 1024 -WarmupSeconds 10
    }
}
if ($IncludeChain) {
    # 首轮端到端尾延迟差异较大；交换运行顺序，验证是否可重复归因于实现。
    foreach ($repeat in 1..2) {
        $versions = if ($repeat -eq 1) { @('final', 'baseline') } else { @('baseline', 'final') }
        foreach ($version in $versions) {
            & "$PSScriptRoot/RunTcpLoad.ps1" -RunName "chain-confirm-$version-$repeat" -EvidenceRoot $Evidence `
                -ServerJar "$Evidence/$version.jar" -ClientJar "$Evidence/final.jar" `
                -DriverClasses "$Evidence/load-classes" -Generated -Gc G1 `
                -Connections 1000 -Rate 2000 -Seconds 60 -PayloadBytes 1024 -WarmupSeconds 10
        }
    }
}
