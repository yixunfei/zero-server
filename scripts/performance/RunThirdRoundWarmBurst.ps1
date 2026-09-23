param([string]$Evidence = 'target/performance-third-20260923')
# 原有 23 个 driver class 原样复制，新增入口仅把客户端 TLS 首次初始化移到计时前。
$ErrorActionPreference = 'Stop'
$warmClasses = "$Evidence/load-warm-classes"
if (Test-Path -LiteralPath $warmClasses) { throw 'Warm burst classes already exist; preserve previous evidence' }
Copy-Item -LiteralPath "$Evidence/load-classes" -Destination $warmClasses -Recurse
& 'D:/env/jdk21/bin/javac.exe' -proc:none -encoding UTF-8 -cp "$warmClasses;$Evidence/final.jar" `
    -d $warmClasses 'scripts/performance/WarmConnectionBurstProbe.java'
if ($LASTEXITCODE -ne 0) { throw 'Warm burst driver compilation failed' }
foreach ($backlog in @(128, 512, 1024)) {
    foreach ($repeat in 1..3) {
        & "$PSScriptRoot/RunTcpLoad.ps1" -RunName "burst-warm-tls-$backlog-$repeat" -EvidenceRoot $Evidence `
            -ServerJar "$Evidence/final.jar" -ClientJar "$Evidence/final.jar" -DriverClasses $warmClasses `
            -Burst -WarmBurstClient -Generated -Connections 1000 -Backlog $backlog `
            -TlsKeyStore "$Evidence/load-tls.p12"
    }
}
