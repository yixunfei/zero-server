param([ValidateRange(1, 5000)][int]$Messages = 100, [ValidateRange(1, 20)][int]$Runs = 3)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runRoot = Join-Path $repo ('target/codegen-measure/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$source = [Text.StringBuilder]::new()
for ($index = 0; $index -lt $Messages; $index++) {
    [void]$source.Append("struct Item$index {`nlong id`nstring name`nlist<int> values`n}`n")
}
$dsl = Join-Path $runRoot 'Benchmark.si'
[IO.File]::WriteAllText($dsl, $source.ToString(), [Text.UTF8Encoding]::new($false))
$jar = @(Get-ChildItem (Join-Path $repo 'zero-codegen/target') -Filter '*-all.jar')
if ($jar.Count -ne 1) { throw 'Build zero-codegen first; exactly one *-all.jar is required.' }
$java = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
$measurements = @()
for ($run = 0; $run -lt $Runs; $run++) {
    $output = Join-Path $runRoot "generated-$run"
    foreach ($phase in @('initial', 'unchanged')) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $json = & $java -jar $jar[0].FullName --input $dsl --out $output --languages java,csharp,typescript,gdscript --json
        if ($LASTEXITCODE -ne 0) { throw "Measurement failed: $json" }
        $watch.Stop()
        $report = $json | ConvertFrom-Json
        $measurements += [pscustomobject]@{ run = $run; phase = $phase; messages = $Messages;
            totalMillis = $watch.Elapsed.TotalMilliseconds; renderMillis = $report.renderMillis;
            outputMillis = $report.outputMillis; files = $report.files.Count;
            changedFiles = @($report.files | Where-Object { $_.status -in @('create', 'update', 'delete') }).Count }
        if ($phase -eq 'unchanged' -and $measurements[-1].changedFiles -ne 0) { throw 'Unchanged run rewrote files.' }
    }
}
$evidence = Join-Path $runRoot 'measurements.json'
$measurements | ConvertTo-Json | Set-Content $evidence -Encoding utf8
$measurements | Format-Table
Write-Output "Evidence: $evidence"
