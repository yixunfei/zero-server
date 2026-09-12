#requires -Version 7.0
[CmdletBinding()]
param([switch]$Plan,[switch]$Partition)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$composeFile=Join-Path $root 'examples/kafka-nacos-cluster/compose.yaml'
$runId=[guid]::NewGuid().ToString('N'); $project="zero-kafka-nacos-cluster-$runId"
$out=Join-Path $root "target/kafka-nacos-cluster/$runId"; $compose=@('compose','-f',$composeFile,'-p',$project)
if($Plan){
 Write-Output 'kafka-nacos-cluster-plan=kafka-brokers:3,nacos-nodes:2,mysql:1'
 Write-Output "compose=$composeFile"
 Write-Output "faults=broker-restart,nacos-restart,network-disconnect-reconnect|partition=$($Partition.IsPresent)"
 Write-Output 'cleanup=only-this-compose-project|containers=true|volumes=true|networks=true'
 return
}
New-Item -ItemType Directory -Path $out -Force|Out-Null
$keys=@('ZERO_KAFKA_1_PORT','ZERO_KAFKA_2_PORT','ZERO_KAFKA_3_PORT','ZERO_NACOS_1_HTTP_PORT','ZERO_NACOS_1_GRPC_PORT','ZERO_NACOS_2_HTTP_PORT','ZERO_NACOS_2_GRPC_PORT','ZERO_KAFKA_BOOTSTRAP_SERVERS','ZERO_NACOS_SERVER_ADDR');$old=@{}
foreach($k in $keys){$old[$k]=[Environment]::GetEnvironmentVariable($k,'Process')}
function Run([string]$exe,[string[]]$CmdArgs,[string]$log){$text=& $exe @CmdArgs 2>&1|Out-String;$code=$LASTEXITCODE;if($log){$text|Set-Content (Join-Path $out $log) -Encoding utf8};Write-Host $text;if($code-ne 0){throw "${exe} failed ${code}: $log"}}
function Port{$l=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);try{$l.Start();return [int]$l.LocalEndpoint.Port}finally{$l.Stop()}}
function Safe-Port{do{$p=Port}while($p -gt 60000);return $p}
$failure=$null
try{
 $env:ZERO_KAFKA_1_PORT=Safe-Port;$env:ZERO_KAFKA_2_PORT=Safe-Port;$env:ZERO_KAFKA_3_PORT=Safe-Port
 $env:ZERO_NACOS_1_HTTP_PORT=Safe-Port;$env:ZERO_NACOS_1_GRPC_PORT=([int]$env:ZERO_NACOS_1_HTTP_PORT+1000)
 $env:ZERO_NACOS_2_HTTP_PORT=Safe-Port;$env:ZERO_NACOS_2_GRPC_PORT=([int]$env:ZERO_NACOS_2_HTTP_PORT+1000)
 Run docker ($compose+@('up','-d')) 'containers.log'
 Start-Sleep 60
 Run docker ($compose+@('ps')) 'ready.log'
 Run docker ($compose+@('images','--format','json')) 'images.json.log'
 $env:ZERO_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$env:ZERO_KAFKA_1_PORT,127.0.0.1:$env:ZERO_KAFKA_2_PORT,127.0.0.1:$env:ZERO_KAFKA_3_PORT"
 $env:ZERO_NACOS_SERVER_ADDR="127.0.0.1:$env:ZERO_NACOS_1_HTTP_PORT,127.0.0.1:$env:ZERO_NACOS_2_HTTP_PORT"
 $common=@('-B','-ntp','-q','-Dzero.external.tests=true',"-Dzero.kafka.bootstrapServers=$env:ZERO_KAFKA_BOOTSTRAP_SERVERS","-Dzero.kafka.bootstrap=$env:ZERO_KAFKA_BOOTSTRAP_SERVERS","-Dzero.nacos.serverAddr=$env:ZERO_NACOS_SERVER_ADDR")
 Run mvn ($common+@('-pl','zero-rpc-kafka','-am','-Pexternal-tests','verify','-Dit.test=KafkaRpcAdapterExternalIT','-Dfailsafe.failIfNoSpecifiedTests=false')) 'kafka-baseline.log'
 Run mvn ($common+@('-pl','zero-discovery-nacos','-am','-Pexternal-tests','verify','-Dit.test=NacosDiscoveryAdapterExternalIT','-Dfailsafe.failIfNoSpecifiedTests=false')) 'nacos-baseline.log'
 Run docker ($compose+@('restart','kafka-1')) 'kafka-broker-restart.log'; Start-Sleep 25
 Run mvn ($common+@('-pl','zero-rpc-kafka','-am','-Pexternal-tests','verify','-Dit.test=KafkaRpcAdapterExternalIT','-Dfailsafe.failIfNoSpecifiedTests=false')) 'kafka-after-restart.log'
 Run docker ($compose+@('restart','nacos-1')) 'nacos-node-restart.log'; Start-Sleep 35
 Run mvn ($common+@('-pl','zero-discovery-nacos','-am','-Pexternal-tests','verify','-Dit.test=NacosDiscoveryAdapterExternalIT','-Dfailsafe.failIfNoSpecifiedTests=false')) 'nacos-after-restart.log'
 if($Partition){
   $network="$project`_default"; Run docker @('network','disconnect',$network,"$project-kafka-1-1") 'partition-disconnect.log'; Start-Sleep 10
   Run mvn ($common+@('-pl','zero-rpc-kafka','-am','-Pexternal-tests','verify')) 'kafka-partition-observed.log'
   Run docker @('network','connect',$network,"$project-kafka-1-1") 'partition-reconnect.log'; Start-Sleep 30
   Run mvn ($common+@('-pl','zero-rpc-kafka','-am','-Pexternal-tests','verify')) 'kafka-after-partition.log'
 }
}catch{$failure=$_;try{Run docker ($compose+@('logs','--no-color','--tail','500')) 'failure-containers.log'}catch{}}
finally{try{Run docker ($compose+@('down','--volumes','--remove-orphans')) 'cleanup.log'}catch{if($null-eq$failure){$failure=$_}};foreach($k in $keys){[Environment]::SetEnvironmentVariable($k,$old[$k],'Process')}}
if($null-ne$failure){throw $failure}
$remaining=(& docker ps -a --filter "name=$project" --format '{{.Names}}'|Out-String).Trim();if($remaining){throw "containers remain: $remaining"}
Write-Output "kafka-nacos-cluster=ok|crossProcessJvm=true|multiNode=true|partition=$($Partition.IsPresent)|containersRemoved=true|networksRemoved=true|outputDir=$out"
