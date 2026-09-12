[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Arguments = @()
)
$ErrorActionPreference = 'Stop'
$RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$StateDir = if ($env:ZERO_STATE_DIR) { $env:ZERO_STATE_DIR } else { Join-Path $RootDir 'target/zero-entry' }
$PidFile = Join-Path $StateDir 'server.pid'
$MetaFile = Join-Path $StateDir 'server.meta'
$MavenCommand = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { $null }

function Show-Usage {
    @'
zeroServer unified entrypoint
Usage: scripts/zero.ps1 <command> [options]
Commands:
  doctor                         Check Java, Maven, Git and repository paths
  init [options]                 Install SNAPSHOT and generate/run local prototype
  generate [options]             Generate a project with NewLocalGame
  test [--projectDir DIR] [args] Run repository or generated-project tests
  diagnose --projectDir DIR      Inspect a generated project without running it
  run --projectDir DIR           Run generated project and record a controlled PID
  stop                           Stop only the PID recorded by this entrypoint
  help                           Show this help
'@
}
function Assert-Root {
    if (-not (Test-Path (Join-Path $RootDir 'pom.xml')) -or -not (Test-Path (Join-Path $RootDir 'scripts/ZeroLocalDoctor.java'))) {
        throw 'zero: run this command from the zeroServer checkout or its scripts directory'
    }
}
function Invoke-JavaTool([string[]] $ToolArgs) {
    Assert-Root
    $env:ZERO_MAVEN_CMD = Get-MavenCommand
    & java @ToolArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
function Invoke-Doctor { Invoke-JavaTool @('scripts/ZeroLocalDoctor.java') }
function Invoke-Init { Invoke-JavaTool (@('scripts/RunLocalPrototype.java') + $Arguments) }
function Invoke-Generate { Invoke-JavaTool (@('scripts/NewLocalGame.java') + $Arguments) }
function Get-MavenCommand {
    if ($MavenCommand) { return $MavenCommand }
    $wrapper = Join-Path $RootDir 'mvnw.cmd'
    if (Test-Path $wrapper) { return $wrapper }
    if (Get-Command mvn -ErrorAction SilentlyContinue) { return 'mvn' }
    throw 'zero: Maven unavailable; use mvnw.cmd or install Maven 3.9+'
}
function Invoke-Test {
    Assert-Root
    $projectDir = $null; $mavenArgs = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $Arguments.Count; $i++) {
        if ($Arguments[$i] -eq '--projectDir') { if ($i + 1 -ge $Arguments.Count) { throw 'zero: --projectDir requires a value' }; $projectDir = $Arguments[++$i] }
        else { [void]$mavenArgs.Add($Arguments[$i]) }
    }
    if ($projectDir) { & (Get-MavenCommand) -B -ntp -f (Join-Path $projectDir 'pom.xml') test @mavenArgs }
    else { & (Get-MavenCommand) -B -ntp test @mavenArgs }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
function Get-ProjectDir([string] $Name) {
    $value = $null
    for ($i = 0; $i -lt $Arguments.Count; $i++) { if ($Arguments[$i] -eq $Name) { if ($i + 1 -ge $Arguments.Count) { throw "zero: $Name requires a value" }; $value = $Arguments[$i + 1]; break } }
    if (-not $value) { throw "zero: $Name requires a value" }
    return $value
}
function Invoke-Diagnose {
    $projectDir = Get-ProjectDir '--projectDir'
    Invoke-JavaTool @('scripts/InspectLocalScaffold.java', '--projectDir', $projectDir)
}
function Invoke-Run {
    $projectDir = Get-ProjectDir '--projectDir'
    Assert-Root
    if (-not (Test-Path (Join-Path $projectDir 'pom.xml'))) { throw 'zero: run requires a generated project with --projectDir DIR' }
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    if (Test-Path $PidFile) {
        $old = (Get-Content $PidFile -Raw).Trim()
        $oldPid = 0
        $oldProcess = $null
        if ([int]::TryParse($old, [ref]$oldPid)) { $oldProcess = Get-Process -Id $oldPid -ErrorAction SilentlyContinue }
        if ($oldProcess) { throw "zero: a managed server is already running (pid=$oldPid)" }
        Remove-Item $PidFile, $MetaFile -Force -ErrorAction SilentlyContinue
    }
    $log = Join-Path $StateDir 'server.log'
    $errorLog = Join-Path $StateDir 'server.error.log'
    $process = Start-Process -FilePath (Get-MavenCommand) -ArgumentList @('-q', "-Dzero.entry.projectDir=$((Resolve-Path $projectDir).Path)", 'exec:java') -WorkingDirectory (Resolve-Path $projectDir) -RedirectStandardOutput $log -RedirectStandardError $errorLog -PassThru
    Set-Content -Path $PidFile -Value $process.Id -NoNewline
    "pid=$($process.Id)`nprojectDir=$((Resolve-Path $projectDir).Path)" | Set-Content $MetaFile
    "zero-run=started|pid=$($process.Id)|projectDir=$projectDir|log=$log"
}
function Invoke-Stop {
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    if (-not (Test-Path $PidFile)) { 'zero-stop=idle|managed=false'; return }
    $raw = (Get-Content $PidFile -Raw).Trim(); $managedPid = 0
    if (-not [int]::TryParse($raw, [ref]$managedPid)) { Remove-Item $PidFile, $MetaFile -Force -ErrorAction SilentlyContinue; 'zero-stop=cleaned|managed=false'; return }
    $process = Get-Process -Id $managedPid -ErrorAction SilentlyContinue
    if ($process) {
        $metaProject = $null
        if (Test-Path $MetaFile) { $metaProject = ((Get-Content $MetaFile | Where-Object { $_ -like 'projectDir=*' }) -replace '^projectDir=', '') }
        $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $managedPid").CommandLine
        if (-not $commandLine -or $commandLine -notmatch '(?i)mvn' -or ($metaProject -and $commandLine -notlike "*$metaProject*")) { throw "zero: managed PID identity mismatch (pid=$managedPid)" }
        Stop-Process -Id $managedPid -Force
        "zero-stop=stopped|pid=$managedPid"
    } else { "zero-stop=already-stopped|pid=$managedPid" }
    Remove-Item $PidFile, $MetaFile -Force -ErrorAction SilentlyContinue
}
try {
    switch ($Command.ToLowerInvariant()) {
        'doctor' { Invoke-Doctor }
        'init' { Invoke-Init }
        'generate' { Invoke-Generate }
        'test' { Invoke-Test }
        'diagnose' { Invoke-Diagnose }
        'run' { Invoke-Run }
        'stop' { Invoke-Stop }
        'help' { Show-Usage }
        default { Write-Error "zero: unknown command: $Command"; Show-Usage; exit 2 }
    }
} catch { Write-Error $_; exit 1 }
