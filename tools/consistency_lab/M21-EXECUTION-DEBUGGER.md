# M21: offline execution-history debugger

The debugger is a Python-generated, self-contained HTML report. It reads only local M18 history JSON or an M20 bundle, validates inputs with M19 and M20 code, and creates a new file without changing the source. The report runs in a local browser without a server, CDN, database, or network request.

```sh
python3 tools/consistency_lab/debugger.py \
  build/consistency-lab/m20-observed \
  build/consistency-lab/m21-observed.html
open build/consistency-lab/m21-observed.html

# An M18 JSON without M20's externally declared scope stays INCONCLUSIVE.
python3 tools/consistency_lab/debugger.py \
  build/consistency-lab/m18/checkout_retry.json \
  build/consistency-lab/m21-checkout.html
```

The output path must not already exist. Bundle input must include `manifest.json` and `history.json`; if reduction files exist, both `reduction.json` and `reduced_history.json` are required. Input files are capped at 256 KiB each, and M19 caps a history at 32 operations. M20 bundle loading checks its schema, source hash, checker hash, manifest/history agreement, declared scope, and exact checker result. Reduction loading additionally checks its hash, retained operations against the original, and M19 FAIL verdict. Hashes detect isolated alteration, not a producer maliciously rewriting all files. An unbundled M18 JSON is schema and text validated but has no hash or exclusive-scope declaration, so M19 reports INCONCLUSIVE. Malformed, partial, unsupported, or tampered input exits 2 and creates no report.

## Evidence map and reading the view

M18 `plan.operations[i]` supplies actor, step, type, logical request identity, and quantity. `observations[i]` supplies state, outcome, result/rejection/exception, and invocation/completion timestamps. The inspector cites those array positions. Timeline bars use only `invokedNanos` and `completedNanos` from the same test JVM. `UNRESOLVED` has an invocation but no observed completion; `NOT_INVOKED` has no interval. Wall-clock strings are context and are never compared across processes. A completed call before another invocation, or an earlier step from the same actor, establishes mandatory precedence. Overlapping calls have no mandatory order from timing alone. Bar position is **not** a linearization point.

M19's `property`, `verdict`, `reason`, `scope`, `checked_operations`, `explored_states`, and optional `witness` are shown. A PASS witness is one possible sequential explanation under the external exclusive-target/no-unrecorded-writer/logical-identity declaration stored in an M20 manifest. It is not the observed execution order or a proof of persisted database state. A FAIL is an exhaustive contradiction only within the supported inventory or checkout model. An incomplete history, missing exclusive-scope declaration, unsupported outcome, or exhausted checker budget remains INCONCLUSIVE.

M20 `source_kind` labels a bundle as captured test execution or synthetic mutation. The reduction panel lists retained and removed operation IDs. Its derived history is never presented as a new execution. The synthetic stock-1 oversell example is a checker mutation, not an observed application defect. The current M18 histories cover CommerceCore service calls only; the debugger has no provider, DB transaction, Kafka delivery, or fault-injection timing and does not claim cross-store or historical correctness.

Use Tab and Enter/Space to select operation buttons, witness items, history tabs, and zoom controls. On narrow screens panels stack and the timeline scrolls horizontally. The generated report embeds only validated bounded evidence; its JavaScript renders evidence through `textContent`, not HTML insertion. Do not share reports containing private evidence without reviewing them first.

## Qualification

Run the complete offline suite with `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'`. The M21 tests cover overlap, precedence data, PASS witness, FAIL reduction, incomplete and not-invoked observations, schema/tamper rejection, script text rejection, and deterministic HTML. Browser inspection must be reported separately from generation tests. Genuine qualified M18 inventory and checkout artifacts under `build/consistency-lab/m18/` can be rendered locally when present; these files are ephemeral and are not committed examples.
