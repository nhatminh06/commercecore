# Architecture diagrams

`architecture.current.json` and `architecture.future.json` are editable layout sources. Regenerate the standalone SVGs with:

```sh
python3 docs/diagrams/render.py
```

The renderer uses only the Python standard library. The current diagram distinguishes one modular Spring Boot process from the separate Next.js, Caddy (production Compose), Payment Service, Kafka, and two PostgreSQL processes. It marks REST, gRPC, local DB, and asynchronous at-least-once paths. Its `source_refs` field points to the files used to check important relationships; the diagram is not runtime evidence. `PortfolioOutboxPublishingSchedule` requires both `portfolio` and `kafka` profiles.

The lab diagram reflects the implemented M15–M21 tools in their **bounded test-only scopes**. Its arrows describe separate evidence paths, not an integrated cross-system timeline or shared snapshot. M16 records CommerceCore and provider databases in independent read-only transactions; M18 records bounded CommerceCore service calls; M17 faults are exercised separately. M19–M21 analyze only supported operation histories. The diagram is architecture documentation, not runtime evidence. See the [M22 qualification matrix](../roadmap/M22-QUALIFICATION.md) for executed commands, artifacts, and deferred claims.
