param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CodegenArguments)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jar = & (Join-Path $repo 'scripts/ResolveCodegen.ps1') -RepositoryRoot $repo
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
if (-not $CodegenArguments -or $CodegenArguments.Count -eq 0) {
    $inputDirectory = Join-Path $PSScriptRoot '../src/test/resources/protocol-dsl/standard-flow'
    $CodegenArguments = @('--input', $inputDirectory, '--protoId', (Join-Path $inputDirectory 'protoId.txt'),
        '--out', (Join-Path $repo 'target/generated-sources/zero-codegen-standard-flow'),
        '--pkg', 'group.zn.zero.standard', '--languages', 'java,csharp,typescript,gdscript', '--genBoImpl', 'true')
}
& $java -jar $jar @CodegenArguments
exit $LASTEXITCODE
