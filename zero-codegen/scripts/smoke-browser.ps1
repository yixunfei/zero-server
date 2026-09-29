param(
    [Parameter(Mandatory)][string]$SourceDir,
    [Parameter(Mandatory)][string]$WorkDir,
    [string]$BrowserExecutable,
    [switch]$Required,
    [int]$BrowserTimeoutSeconds = 90,
    [string[]]$ExpectedVectors
)
$ErrorActionPreference = 'Stop'
if ($BrowserTimeoutSeconds -lt 1) {
    throw 'BrowserTimeoutSeconds must be at least 1.'
}
if (-not $BrowserExecutable) {
    $candidates = @('C:/Program Files/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/google-chrome', '/usr/bin/chromium')
    $BrowserExecutable = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $BrowserExecutable) {
    if ($Required) { throw 'Chromium/Edge/Chrome is required for full browser validation.' }
    Write-Warning 'Browser unavailable; browser runtime execution pending.'
    return
}
New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
$npx = if ($IsWindows) { 'npx.cmd' } else { 'npx' }
& $npx --yes --package esbuild@0.25.10 esbuild (Join-Path $SourceDir 'wire-vectors.ts') `
    --bundle --platform=browser --target=es2020 "--outfile=$(Join-Path $WorkDir 'vectors.js')"
if ($LASTEXITCODE -ne 0) { throw 'Browser bundle failed.' }
@'
<!doctype html><meta charset="utf-8"><pre id="result"></pre>
<script>
console.log = text => document.getElementById('result').textContent += text + '\n';
window.onerror = text => { document.getElementById('result').textContent += 'FAIL:' + text; };
</script><script src="vectors.js"></script>
'@ | Set-Content (Join-Path $WorkDir 'index.html') -Encoding utf8
$html = ([uri](Join-Path (Resolve-Path $WorkDir).Path 'index.html')).AbsoluteUri
$profile = Join-Path $WorkDir 'profile'
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $BrowserExecutable
$startInfo.WorkingDirectory = $WorkDir
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($argument in @('--headless', '--no-sandbox', '--disable-gpu', '--no-first-run', '--dump-dom',
    "--user-data-dir=$profile", $html)) {
    $startInfo.ArgumentList.Add($argument)
}
$process = [System.Diagnostics.Process]::Start($startInfo)
$stdoutTask = $null
$stderrTask = $null
try {
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($BrowserTimeoutSeconds * 1000)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw 'Browser execution timed out.'
    }
    $browserOutput = $stdoutTask.GetAwaiter().GetResult()
    $browserErrors = $stderrTask.GetAwaiter().GetResult()
    $exitCode = $process.ExitCode
} finally {
    $process.Dispose()
}
$browserOutput | Set-Content (Join-Path $WorkDir 'browser-output.txt') -Encoding utf8
$browserErrors | Set-Content (Join-Path $WorkDir 'browser-errors.txt') -Encoding utf8
if ($exitCode -ne 0 -or -not $browserOutput -or $browserOutput -match '<pre[^>]*>[^<]*FAIL:') {
    throw "Browser payload execution failed; inspect $WorkDir/browser-errors.txt or specify -BrowserExecutable."
}
foreach ($vector in $ExpectedVectors) {
    if (-not $browserOutput.Contains($vector)) { throw "Missing browser vector: $vector" }
}
Write-Output 'Browser payload vectors and invalid-input checks match Java.'
