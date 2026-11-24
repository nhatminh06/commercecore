# M20: offline replay bundles and analytical reduction

[`replay.py`](replay.py) packages M18 version 1 histories and reuses the independent M19 [`history_checker.py`](history_checker.py). It makes no production call. Bundle version 1 is a directory containing the original `history.json` bytes and `manifest.json`. The manifest records the scenario/run ID, seed, exact plan, source kind (`captured_test_execution` or `synthetic_mutation`), provenance, property, explicit exclusive-target/no-unrecorded-writer/logical-identity assumptions, state/time search budgets, original checker result and diagnostics, and SHA-256 hashes of the original history bytes and checker implementation. It carries no raw checkout key, database URL, credential, or customer record. The bundle format is portable; a different checkout of the code can recheck only if its M19 checker file has the recorded hash. SHA-256 detects accidental or isolated file alteration, **not** a malicious rewrite of the history and manifest together.

```sh
# From the repository root, after a successful M18 test run:
python3 tools/consistency_lab/replay.py create \
  build/consistency-lab/m18/inventory_reserve.json build/consistency-lab/m20-observed \
  --source-kind captured_test_execution --exclusive-scope
python3 tools/consistency_lab/replay.py recheck build/consistency-lab/m20-observed

# Only for an exhaustively checked FAIL bundle:
python3 tools/consistency_lab/replay.py reduce build/consistency-lab/m20-synthetic

# Optional, bounded real rerun of an existing M18 scenario (Java 21 and Docker required):
JAVA_HOME=/path/to/jdk-21 python3 tools/consistency_lab/replay.py rerun \
  build/consistency-lab/m20-observed --attempts 1
```

`create` requires a complete, strictly validated M18 history and an explicit scope declaration. The declaration is an external test assumption; the JSON alone cannot prove a unique SKU/key or absence of outside writers. It checks timestamp, rejection-code, and exception-class fields for their expected forms before retaining them. `recheck` verifies the source bytes, manifest/history consistency, checker hash, and exact verdict and diagnostics under the recorded budgets. Altered, incomplete, unsupported, and over-256 KiB evidence is rejected with exit 2. The original file is copied once into a newly created bundle directory and never modified by `recheck`, `reduce`, or `rerun`.

## Reduction

M19 accepts only rectangular M18 plans: all actors have the same step count, actor IDs are contiguous, and retained operation IDs cannot be renumbered. The supported deletion strategy therefore tries (1) removing the last step from every actor, then (2) removing the highest-numbered actor, keeping at least two. It changes only plan shape and derived event counts; retained identities, outcomes, intervals, initial stock, property, and assumptions remain byte-for-byte equal as JSON values. Every candidate goes through M19 validation and exhaustive checking. Only a candidate with the **same property** and `FAIL` verdict is accepted; `PASS`, `INCONCLUSIVE`, or malformed candidates are rejected. The algorithm records every attempted deletion, candidate hash, decision, and checker result in `reduction.json`. The derived `reduced_history.json` is an **analytical projection**, not a history that actually executed. It retains the original seed as provenance; that seed does not regenerate the reduced plan. The original `history.json` remains unchanged.

At most 32 deletion attempts and 10 seconds of reduction-loop time are allowed by default, plus M19's bounded candidate-check budget; CLI caps are 128 attempts and 60 seconds. The result marks local minimality true only if no supported further deletion preserves an exhaustive FAIL. This is not global minimality over every possible subset. A candidate whose search budget is exhausted cannot establish a smaller counterexample. The reduction command refuses an original PASS or INCONCLUSIVE history.

## Execution reruns and observed qualification

The optional `rerun` command supports the two *existing fixed* M18 test plans only. It invokes the isolated `M18WorkloadTest` against disposable PostgreSQL, at most three times, and stores each attempt under `attempts/NN.json` with its own hash, run ID, actual monotonic intervals, completeness, input-plan comparison, and M19 result. The runner uses a new run ID each time; it compares the remaining plan inputs. It never overwrites the bundled original. A target FAIL that does not recur is `NOT_REPRODUCED` with the count and outcomes. A PASS original has `NO_TARGET_FAILURE`. Scheduling and database lock order are not replayed by the seed. The command inherits `JAVA_HOME` and local Docker availability, has a 180-second bound per Gradle attempt, and runs no production deployment or payment authorization.

Local qualification on 2026-11-24: the fresh observed four-operation inventory history was complete and `PASS` under the declared unique-SKU scope. Offline `recheck` reproduced its exact verdict. One real M18 rerun had the same input plan apart from run ID, was complete, and returned `PASS`; the report said `NO_TARGET_FAILURE`, one attempt. The deliberately **synthetic** stock-1 mutation with two successful reservations plus two irrelevant later rejections was `FAIL`. Offline `recheck` reproduced `FAIL`; reduction removed the two later operations, leaving a two-operation exhaustive `FAIL` with local minimality true. A separate synthetic conflicting-checkout-ID case also reduced from four operations to two in tests. These mutations qualify the tooling, **not** a defect observed in CommerceCore. No captured application failure was reproduced or reduced.

Run `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'` for the offline suite. `LAB_POSTGRES_TEST=1` adds the isolated M16 recorder tests. Hosted CI runs the offline Python tests; Docker-backed tests are opt-in. M20 does not add an M21 viewer or claim global linearizability, cross-store atomicity, or runtime schedule determinism.
