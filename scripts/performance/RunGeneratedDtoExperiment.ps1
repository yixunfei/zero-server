param(
    [Parameter(Mandatory = $true)][string]$Jdk,
    [string]$Evidence = 'target/performance-incremental-20260923',
    [string]$Processor = "$env:USERPROFILE/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar"
)
# 生成物只写 target；实验代码独立编译，不为业务模块增加 codegen/JMH 依赖。
$ErrorActionPreference = 'Stop'
$output = "$Evidence/generated-dto"
$classes = "$Evidence/generated-experiment-classes"
& "$Jdk/bin/java.exe" -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar --input scripts/performance/fixtures --out $output --pkg group.zn.zero.benchmark.generated --languages java
if ($LASTEXITCODE -ne 0) { throw 'Fixture codegen failed' }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = @(Get-ChildItem $output -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
$sources += 'scripts/performance/GeneratedDtoSizingBenchmark.java'
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) "$Evidence/generated-sources.args"), $sources)
& "$Jdk/bin/javac.exe" -encoding UTF-8 -cp "$Evidence/final.jar" -processorpath "$Processor;$Evidence/final.jar" -processor org.openjdk.jmh.generators.BenchmarkProcessor -d $classes "@$Evidence/generated-sources.args"
if ($LASTEXITCODE -ne 0) { throw 'Generated benchmark compile failed' }
& "$Jdk/bin/java.exe" -cp "$classes;$Evidence/final.jar" org.openjdk.jmh.Main 'GeneratedDtoSizingBenchmark.(generated|planned)' -foe true -f 2 -wi 2 -i 3 -w 500ms -r 500ms -prof gc -jvmArgs '-Xms512m -Xmx512m -XX:+UseG1GC' -rf json -rff "$Evidence/sizing.json" *> "$Evidence/sizing.log"
if ($LASTEXITCODE -ne 0) { throw 'Sizing experiment failed' }
foreach ($library in @('baseline', 'final')) {
    $pattern = if ($library -eq 'baseline') { 'GeneratedDtoSizingBenchmark.decode(Array|View)' } else { 'GeneratedDtoSizingBenchmark.decode(Array|View|Frame)' }
    & "$Jdk/bin/java.exe" -cp "$classes;$Evidence/$library.jar" org.openjdk.jmh.Main $pattern -p backend=heap -p depth=1,8 -p size=16,1024 -foe true -f 2 -wi 2 -i 3 -w 500ms -r 500ms -prof gc -jvmArgs '-Xms512m -Xmx512m -XX:+UseG1GC' -rf json -rff "$Evidence/dto-$library.json" *> "$Evidence/dto-$library.log"
    if ($LASTEXITCODE -ne 0) { throw "DTO decode experiment failed: $library" }
}
