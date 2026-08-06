@echo off
setlocal

rem zero-codegen one-click CLI launcher.
rem Copy this file and edit the variables below for client projects.
rem zero-codegen requires Java 21 or newer.

set "SCRIPT_DIR=%~dp0"
set "CODEGEN_JAR=%SCRIPT_DIR%..\target\zero-codegen-0.1.0-SNAPSHOT-all.jar"
set "JAVA_EXE=java"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "INPUT_DIR=%SCRIPT_DIR%..\src\test\resources\protocol-dsl\standard-flow"
set "PROTO_ID=%INPUT_DIR%\protoId.txt"
set "OUT_DIR=%SCRIPT_DIR%..\..\target\generated-sources\zero-codegen-standard-flow"
set "JAVA_PKG=group.zn.zero.standard"
set "LANGUAGES=java,csharp,typescript,gdscript"
set "GEN_BO_IMPL=true"

rem Optional Java server-side layout overrides.
rem Leave these variables empty to use defaults derived from JAVA_PKG.
set "OUT_JAVA_DTO="
set "OUT_JAVA_CODEC="
set "OUT_JAVA_PROTOCOL="
set "OUT_JAVA_BO="
set "OUT_JAVA_BO_IMPL="
set "OUT_JAVA_DISPATCHER="
set "JAVA_DTO_PKG="
set "JAVA_CODEC_PKG="
set "JAVA_PROTOCOL_PKG="
set "JAVA_BO_PKG="
set "JAVA_BO_IMPL_PKG="
set "JAVA_DISPATCHER_PKG="

if not exist "%CODEGEN_JAR%" (
  echo Missing codegen jar: "%CODEGEN_JAR%"
  echo Run: mvn -pl zero-codegen -am package
  pause
  exit /b 1
)

set "EXTRA_ARGS="
if not "%OUT_JAVA_DTO%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaDto ^"%OUT_JAVA_DTO%^""
if not "%OUT_JAVA_CODEC%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaCodec ^"%OUT_JAVA_CODEC%^""
if not "%OUT_JAVA_PROTOCOL%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaProtocol ^"%OUT_JAVA_PROTOCOL%^""
if not "%OUT_JAVA_BO%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaBo ^"%OUT_JAVA_BO%^""
if not "%OUT_JAVA_BO_IMPL%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaBoImpl ^"%OUT_JAVA_BO_IMPL%^""
if not "%OUT_JAVA_DISPATCHER%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --outJavaDispatcher ^"%OUT_JAVA_DISPATCHER%^""
if not "%JAVA_DTO_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaDtoPkg ^"%JAVA_DTO_PKG%^""
if not "%JAVA_CODEC_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaCodecPkg ^"%JAVA_CODEC_PKG%^""
if not "%JAVA_PROTOCOL_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaProtocolPkg ^"%JAVA_PROTOCOL_PKG%^""
if not "%JAVA_BO_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaBoPkg ^"%JAVA_BO_PKG%^""
if not "%JAVA_BO_IMPL_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaBoImplPkg ^"%JAVA_BO_IMPL_PKG%^""
if not "%JAVA_DISPATCHER_PKG%"=="" set "EXTRA_ARGS=%EXTRA_ARGS% --javaDispatcherPkg ^"%JAVA_DISPATCHER_PKG%^""

"%JAVA_EXE%" -jar "%CODEGEN_JAR%" ^
  --input "%INPUT_DIR%" ^
  --protoId "%PROTO_ID%" ^
  --out "%OUT_DIR%" ^
  --pkg "%JAVA_PKG%" ^
  --languages "%LANGUAGES%" ^
  --genBoImpl "%GEN_BO_IMPL%" ^
  %EXTRA_ARGS%

set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo zero-codegen exit code: %EXIT_CODE%
pause
exit /b %EXIT_CODE%
