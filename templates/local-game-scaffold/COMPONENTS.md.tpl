# __PROJECT_NAME__ Components

This document explains how this generated zeroServer local/prototype project is assembled.

## Template

- Template: `__TEMPLATE_NAME__`
- Description: __TEMPLATE_DESCRIPTION__
- Use case: __TEMPLATE_USE_CASE__
- Protocol file: `__PROTOCOL_FILE__`
- Expected summary prefix: `__SUMMARY_PREFIX__`

Selected components: __SELECTED_COMPONENTS__.
Runtime profile: __RUNTIME_PROFILE__. Configuration sample: `config/application.properties.example`.
Load an external properties file with `ZERO_CONFIG_FILE`. Protocol generation uses a build plugin dependency.

## Manifest

`zero-scaffold.json` contains machine-readable scaffold metadata for tools. It is not a production deployment descriptor.

## Business Guide

`BUSINESS_GUIDE.md` shows where to edit protocol, where to place handwritten BO logic, how to run local verification and which changes must stay out of this local scaffold.

## Next Steps

`NEXT_STEPS.md` explains the promotion checklist, production gap and high-risk stop points before turning this scaffold into a formal framework module.

## Component Flow

~~~text
__PROTOCOL_FILE__
  -> Maven generate-sources / zero-codegen
  -> generated DTO / codec / BO / GeneratedProtocolDispatcher
  -> LocalGameBO (async business adapter)
  -> LocalGameFlow / LocalGameFixture composition
  -> injected player/scene service ports
  -> local Actor lane state mutation
  -> LocalGameObservation, log sink and monitor registry
~~~

## Framework Touchpoints

| Component | Purpose in this scaffold |
| --- | --- |
| `zero-codegen` | Generates protocol DTO, codec, BO and dispatcher from `.si`. |
| `zero-protocol` | Provides payload encoding and generated codec contracts. |
| `zero-runtime-bootstrap` / selected `zero-runtime-*` | Provides explicit runtime assembly in `RuntimeAssembly.java`. |
| `zero-actor` | Serializes state mutation through local lanes where the template needs it. |
| `zero-log` | Records local business actions. |
| `zero-monitor` | Records local metric samples. |

## Local Boundary

This scaffold is intentionally local / prototype:

- The default selection does not connect external middleware. Adding Redis requires an external service at startup.
- It does not open production network endpoints.
- It does not provide production authentication, authorization, persistence, capacity, backpressure or long-running guarantees.

## Production Gap

__PRODUCTION_GAP__

Before turning this scaffold into a formal framework module, define public API, ErrorCode, thread and Actor boundaries, data/cache strategy, observability, distributed semantics and production verification.
