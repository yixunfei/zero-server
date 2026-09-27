param(
    [string]$GodotExecutable
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$codegen = Join-Path $repo 'zero-codegen'
$fixture = Join-Path $codegen 'src\test\interop'
$runRoot = Join-Path $repo ('target\codegen-interop\' + [guid]::NewGuid().ToString('N'))
$generated = Join-Path $runRoot 'generated'
$javaClasses = Join-Path $runRoot 'java-classes'
$csWork = Join-Path $runRoot 'csharp-app'
$tsWork = Join-Path $runRoot 'typescript-src'
$tsClasses = Join-Path $runRoot 'typescript-classes'
$gdWork = Join-Path $runRoot 'godot-project'
$expectedVectors = @(
    'role=149593d89fee47035a6f65040874726163652d3432',
    'snapshot=2922010154035a6f650eaab4de75eaadc0e52401020209010477696e7304054775696c6480a0abfef962'
)

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$step failed with exit code $LASTEXITCODE"
    }
}

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME must point to JDK 21.'
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
if (-not (Test-Path $javac)) {
    throw 'JAVA_HOME must point to a JDK with javac.'
}
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
Push-Location $repo
try {
    & mvn -pl zero-codegen -am package -q -DskipTests
    Assert-Exit 'Maven package'

    $jar = Join-Path $codegen 'target\zero-codegen-0.1.0-SNAPSHOT-all.jar'
    $dsl = Join-Path $codegen 'src\test\resources\protocol-dsl\standard-flow'
    & $java -jar $jar --input $dsl --protoId (Join-Path $dsl 'protoId.txt') `
        --out $generated --pkg group.zn.zero.standard `
        --languages java,csharp,typescript,gdscript --genBoImpl true
    Assert-Exit 'four-target generation'

    New-Item -ItemType Directory -Path $javaClasses -Force | Out-Null
    $moduleClasses = @(Get-ChildItem $repo -Directory -Filter 'zero-*' |
        ForEach-Object { Join-Path $_.FullName 'target\classes' } | Where-Object { Test-Path $_ })
    $classpath = $moduleClasses -join [IO.Path]::PathSeparator
    $javaSources = @(Get-ChildItem $generated -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac -cp $classpath -d $javaClasses @javaSources (Join-Path $fixture 'java\WireVectors.java')
    Assert-Exit 'generated Java compile'
    $javaVectors = @(& $java -cp ($javaClasses + [IO.Path]::PathSeparator + $classpath) WireVectors)
    Assert-Exit 'Java payload vectors'
    if (($javaVectors -join "`n") -ne ($expectedVectors -join "`n")) {
        throw "Java payload bytes differ from the known vectors: $($javaVectors -join ', ')"
    }

    & dotnet new console --output $csWork --framework net8.0 --no-restore | Out-Null
    Assert-Exit 'create C# harness'
    Copy-Item (Join-Path $fixture 'csharp\Program.cs') (Join-Path $csWork 'Program.cs') -Force
    Copy-Item (Join-Path $generated 'csharp\*.cs') $csWork
    & dotnet build $csWork --configuration Release --verbosity quiet
    Assert-Exit 'generated C# compile'
    $csVectors = @(& dotnet (Join-Path $csWork 'bin\Release\net8.0\csharp-app.dll'))
    Assert-Exit 'C# payload vectors'

    Copy-Item (Join-Path $generated 'typescript') $tsWork -Recurse
    Copy-Item (Join-Path $fixture 'typescript\wire-vectors.ts') $tsWork
    $tsSources = @(Get-ChildItem $tsWork -Filter '*.ts' | ForEach-Object FullName)
    & npx.cmd --yes --package typescript@5.9.3 tsc --outDir $tsClasses `
        --target es2020 --module commonjs --strict @tsSources
    Assert-Exit 'generated TypeScript compile'
    $tsVectors = @(& node (Join-Path $tsClasses 'wire-vectors.js'))
    Assert-Exit 'TypeScript payload vectors'

    foreach ($entry in @(@('C#', $csVectors), @('TypeScript', $tsVectors))) {
        if (($javaVectors -join "`n") -ne ($entry[1] -join "`n")) {
            throw "$($entry[0]) payload bytes differ from Java: $($entry[1] -join ', ')"
        }
    }

    if (-not $GodotExecutable) {
        $godot = Get-Command godot, godot4 -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($godot) { $GodotExecutable = $godot.Source }
    }
    if ($GodotExecutable) {
        New-Item -ItemType Directory -Path $gdWork -Force | Out-Null
        Copy-Item (Join-Path $generated 'gdscript\zero_protocol.gd') $gdWork
        Copy-Item (Join-Path $fixture 'gdscript\*') $gdWork
        $gdOutput = @(& $GodotExecutable --headless --path $gdWork `
            --script res://wire_vectors.gd --quit-after 60 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "GDScript payload vectors failed with exit code $LASTEXITCODE`n$($gdOutput -join "`n")"
        }
        $gdVectors = @($gdOutput | Where-Object { $_ -match '^(role|snapshot)=' })
        if (($javaVectors -join "`n") -ne ($gdVectors -join "`n")) {
            throw "GDScript payload bytes differ from Java: $($gdVectors -join ', ')`n$($gdOutput -join "`n")"
        }
        Write-Output 'GDScript runtime vectors match Java.'
    } else {
        $gdSource = Get-Content (Join-Path $generated 'gdscript\zero_protocol.gd') -Raw
        foreach ($name in @('RoleCreateRoleProtocolDTOCodec', 'PlayerPlayerSnapshotProtocolDTOCodec')) {
            if (-not $gdSource.Contains("class ${name}:")) {
                throw "GDScript output is missing $name"
            }
        }
        Write-Warning 'Godot unavailable; GDScript structure checked, runtime execution pending.'
    }
    Write-Output 'Java, C# and TypeScript payload vectors match:'
    $javaVectors
    Write-Output "Artifacts: $runRoot"
} finally {
    Pop-Location
}
