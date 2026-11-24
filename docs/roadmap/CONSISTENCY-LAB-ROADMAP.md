# CommerceCore Consistency Lab roadmap

**M15a, M15b, and M16** are implemented here. M17 onward is **PLANNED**. This test-only lab must remain outside production request paths. Gates require evidence, not diagrams or passing synthetic tests alone.

| Milestone | Scope and deliverable | Required evidence and acceptance | Risks, limits, non-goals |
|---|---|---|---|
| M15a IMPLEMENTED | Versioned synthetic evidence contract, offline oracle, fixture, mutation tests. | Positive, violated, incomplete, malformed, deterministic tests for every supported snapshot property; no unsupported PASS. | Synthetic data does not prove the running system. No collector, history, or real-run verdict. |
| M15b IMPLEMENTED | Qualify repo-native oracle; offline CI; current and future architecture diagrams. | Independent review of assumptions, mutation tests in CI, source-backed diagram review. | CI tests the checker only; GitHub-hosted execution remains unverified until a workflow run completes. |
| M16 IMPLEMENTED | Read-only, bounded capture of four CommerceCore tables with one PostgreSQL snapshot; v2 offline oracle validation. | Isolated real PostgreSQL capture, SELECT-only role, count/schema/identity checks, concurrent-commit test, PASS/FAIL/INCONCLUSIVE qualification. | No provider store or application-operation history is captured. `run_id` labels the capture but does not correlate all rows to a scenario; no cross-store correctness claim. |
| M17 PLANNED | Test-only Toxiproxy around real gRPC calls. | Bounded timeout and outage cases, cleanup proof, remote authorization/local UNKNOWN without duplicate authorization. | Fault schedules may race. No production proxy or public controls. |
| M18 PLANNED | Seeded, bounded concurrent workload and recorded actual operations. | Repeatable input plan plus invocation/result identities and observed schedule. | Seed cannot reproduce thread interleavings. No load benchmark. |
| M19 PLANNED | Independent history checker for selected state-machine and concurrency properties. | Complete histories, mutation counterexamples, explicit assumptions and uncertainty. | No global linearizability claim from snapshots or wall clocks. |
| M20 PLANNED | Replay bundles and counterexample reduction. | One reproduced and reduced injected failure, with reproduction status and retained provenance. | Some races cannot be replayed exactly. No claim of deterministic scheduling. |
| M21 PLANNED | Offline execution-history debugger. | Every displayed assertion links to bounded captured evidence; observed and inferred ordering visually distinct. | Visualization cannot strengthen evidence. No live production dashboard. |
| M22 PLANNED | Integrated qualification and portfolio evidence. | Repeatable isolated scenario matrix, CI evidence, documented limits, independent reproduction. | Local experiments do not establish production availability or exhaustive correctness. No deployment automation. |
| M23 OPTIONAL, PLANNED | TLA+ model of selected domain transitions. | Explicit model assumptions, bounded counterexamples, model-to-code correspondence. | Model checking does not verify Java implementation. No whole-system formal proof. |
