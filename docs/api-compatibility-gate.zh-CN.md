# P0-5 API/SPI Compatibility Gate

`compatibility-gate` is an explicit verification entry, not part of the default reactor lifecycle. Run it after compiling the protected modules:

```bash
JAVA_HOME=/d/env/jdk21 PATH=/d/env/jdk21/bin:$PATH \
./mvnw -B -ntp -DskipTests -pl zero-core,zero-runtime,zero-protocol,zero-rpc-common,zero-data -am install
java scripts/VerifyPublicApiCompatibility.java --check
java scripts/VerifyApiCompatibilityConsumer.java
```

The gate allows additive public API but rejects removal or signature changes relative to the tracked bootstrap manifests. It is not a complete historical ABI guarantee; future work should replace or supplement it with japicmp/Revapi and configuration/protocol/provider-ID diffs.
