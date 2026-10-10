[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Arguments = @()
)
$ErrorActionPreference = 'Stop'
$RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$MavenCommand = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } elseif ($env:ZERO_MAVEN_CMD) { $env:ZERO_MAVEN_CMD } else { $null }

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
  run --projectDir DIR           Run foreground demo; nonzero on failure
  demo --projectDir DIR          Alias for run
  serve --projectDir DIR         Start generated Server and wait for readiness
  status                         Report verified JVM identity and readiness
  stop                           Request graceful shutdown and wait
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
    Push-Location $RootDir
    try { & java @ToolArgs } finally { Pop-Location }
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
function Invoke-ProcessCommand {
    Invoke-JavaTool (@('scripts/ZeroProcess.java', $Command.ToLowerInvariant()) + $Arguments)
}

try {
    switch ($Command.ToLowerInvariant()) {
        'doctor' { Invoke-Doctor }
        'init' { Invoke-Init }
        'generate' { Invoke-Generate }
        'test' { Invoke-Test }
        'diagnose' { Invoke-Diagnose }
        'run' { Invoke-ProcessCommand }
        'demo' { Invoke-ProcessCommand }
        'serve' { Invoke-ProcessCommand }
        'status' { Invoke-ProcessCommand }
        'stop' { Invoke-ProcessCommand }
        'help' { Show-Usage }
        default { [Console]::Error.WriteLine("zero: unknown command: $Command"); Show-Usage; exit 2 }
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    if ($env:ZERO_DEBUG -eq '1') { [Console]::Error.WriteLine($_.ScriptStackTrace) }
    exit 1
}
