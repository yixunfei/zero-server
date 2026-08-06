param(
    [string] $JavaHome = "D:\env\jdk21",
    [string] $MavenHome = "D:\env\apache-maven-3.9.8"
)

$ErrorActionPreference = "Stop"

function Require-Directory {
    param(
        [string] $Path,
        [string] $Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Name directory not found: $Path"
    }
}

Require-Directory -Path $JavaHome -Name "JDK"
Require-Directory -Path $MavenHome -Name "Maven"

$javaBin = Join-Path $JavaHome "bin"
$mavenBin = Join-Path $MavenHome "bin"
Require-Directory -Path $javaBin -Name "JDK bin"
Require-Directory -Path $mavenBin -Name "Maven bin"

$env:JAVA_HOME = $JavaHome
$env:Path = "$mavenBin;$javaBin;$env:Path"

Write-Host "zeroServer dev environment is ready for this PowerShell process."
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host ""
& java -version
Write-Host ""
& mvn -version
