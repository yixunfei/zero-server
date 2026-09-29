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
$browserUrl = $html
$httpServer = $null
$python = Get-Command python3, python -ErrorAction SilentlyContinue | Select-Object -First 1
if ($python) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $serverInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $serverInfo.FileName = $python.Source
    $serverInfo.WorkingDirectory = $WorkDir
    $serverInfo.UseShellExecute = $false
    $serverInfo.CreateNoWindow = $true
    $serverInfo.RedirectStandardOutput = $true
    $serverInfo.RedirectStandardError = $true
    foreach ($argument in @('-m', 'http.server', $port, '--bind', '127.0.0.1', '--directory', $WorkDir)) {
        $serverInfo.ArgumentList.Add($argument)
    }
    $httpServer = [System.Diagnostics.Process]::Start($serverInfo)
    $browserUrl = "http://127.0.0.1:$port/index.html"
    $serverReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $browserUrl -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                $serverReady = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    if (-not $serverReady) {
        $httpServer.Kill($true)
        $httpServer.Dispose()
        $httpServer = $null
        $browserUrl = $html
    }
}
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $BrowserExecutable
$startInfo.WorkingDirectory = $WorkDir
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($argument in @('--headless=new', '--no-sandbox', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--disable-background-networking', '--dump-dom',
    '--disable-dev-shm-usage', '--allow-file-access-from-files', '--virtual-time-budget=5000',
    "--user-data-dir=$profile", $browserUrl)) {
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
    if ($httpServer) {
        if (-not $httpServer.HasExited) { $httpServer.Kill($true) }
        $httpServer.Dispose()
    }
}
$browserOutput | Set-Content (Join-Path $WorkDir 'browser-output.txt') -Encoding utf8
$browserErrors | Set-Content (Join-Path $WorkDir 'browser-errors.txt') -Encoding utf8
if ($exitCode -ne 0 -or -not $browserOutput -or $browserOutput -match '<pre[^>]*>[^<]*FAIL:') {
    throw "Browser payload execution failed; inspect $WorkDir/browser-output.txt and $WorkDir/browser-errors.txt or specify -BrowserExecutable."
}
foreach ($vector in $ExpectedVectors) {
    if (-not $browserOutput.Contains($vector)) {
        throw "Missing browser vector: $vector; inspect $WorkDir/browser-output.txt and $WorkDir/browser-errors.txt."
    }
}
Write-Output 'Browser payload vectors and invalid-input checks match Java.'
