# __PROJECT_NAME__ Next Steps

This file explains how to move from this generated local/prototype scaffold toward a formal zeroServer component or a production-ready game service.

## Current Template

- Template: `__TEMPLATE_NAME__`
- Use case: __TEMPLATE_USE_CASE__
- Protocol file: `__PROTOCOL_FILE__`
- Local smoke summary: `__SUMMARY_PREFIX__`

## What Is Ready Locally

- The `.si` protocol can generate DTO, codec, BO and dispatcher code.
- The handwritten BO implementation is wired to the generated dispatcher.
- The default local composition runs without Docker or external middleware.
- The smoke test and `RunLocalScaffold` verify the local/prototype flow.

## Production Gap

__PRODUCTION_GAP__

Before using this shape as a formal framework module, define:

- Public API request and response model.
- ErrorCode ownership and compatibility rules.
- Actor lane ownership, backpressure and cross-Actor message boundaries.
- Repository / Cache persistence and failure degradation strategy.
- TraceId, business log, performance log and metric names.
- Distributed RPC, discovery, idempotency and failure drill behavior.
- Security, GM, approval and audit requirements where applicable.

## High-Risk Stop Points

Pause and create a separate task before changing:

- Public API, SPI, annotations or protocol DSL rules.
- Thread model, Actor model, lane routing or executor ownership.
- Network protocol, protocol IDs, codec format or compatibility strategy.
- Storage format, cache key format, dirty tracking or persistence flow.
- GM permissions, RBAC, IP whitelist, approval flow or audit log fields.
- Module names, package layout or dependency direction.

## Recommended Commands

Run from the zeroServer repository root and replace the project directory as needed:

~~~powershell
mvn -q -DskipTests install
java scripts/RunLocalScaffold.java --projectDir path\to\__PROJECT_NAME__
~~~

Before extracting a formal framework module or changing a public contract, open a GitHub Design Proposal and describe the API, thread, compatibility, performance, security and verification boundaries.

If this project lives under `target`, pass that generated directory to `--projectDir`.

## Promotion Checklist

- [ ] Keep this project local/prototype until the production gap above is resolved.
- [ ] Open a Design Proposal before extracting a formal module.
- [ ] Add focused tests before changing public contracts.
- [ ] Update `docs/module-map.md` if module structure or dependency direction changes.
- [ ] Document any breaking change in `CHANGELOG.md` and migration notes.
