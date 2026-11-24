# Architecture diagrams

`architecture.current.json` and `architecture.future.json` are editable layout sources. Regenerate the standalone SVGs with:

```sh
python3 docs/diagrams/render.py
```

The renderer uses only the Python standard library. The current diagram distinguishes one modular Spring Boot process from the separate Next.js, Caddy (production Compose), Payment Service, Kafka, and two PostgreSQL processes. It marks REST, gRPC, local DB, and asynchronous at-least-once paths. Its `source_refs` field points to the files used to check important relationships; the diagram is not runtime evidence. `PortfolioOutboxPublishingSchedule` requires both `portfolio` and `kafka` profiles.

The future diagram is a proposal. Only the synthetic offline oracle exists in M15a/M15b. Fault injection, workload generation, real collectors, history verification, replay, and debugger remain **PLANNED / NOT IMPLEMENTED**. The M15b CI job checks only the offline oracle tests and synthetic fixture. It starts no application process or database and proves no live distributed guarantee.

For M16, a collector must identify each run, read bounded CommerceCore and provider state under documented per-store consistency boundaries, prove row coverage, record operations and outcomes, and disclose uncertainty between independently sampled stores. The v1 oracle deliberately rejects real captures until that contract is qualified.
