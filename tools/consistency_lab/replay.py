"""Offline M18/M19 replay bundles and bounded analytical counterexample reduction."""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from history_checker import InvalidHistory, check, validate


HERE = Path(__file__).resolve().parent
CHECKER = HERE / "history_checker.py"
MAX_BYTES = 262_144
ISO_TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def bounded_read(path):
    path = Path(path)
    if path.stat().st_size > MAX_BYTES:
        raise InvalidHistory("file exceeds 256 KiB")
    return path.read_bytes()


def checked_history(raw):
    history = json.loads(raw)
    validate(history)
    if not history["complete"]:
        raise InvalidHistory("bundle requires a complete M18 history")
    timestamps = [history["startedAt"], history["finishedAt"]]
    for event in history["observations"]:
        timestamps.extend((event["invokedAt"], event["completedAt"]))
        if event["rejectionCode"] is not None and not re.fullmatch(r"[a-z][a-z_]{0,63}", event["rejectionCode"]):
            raise InvalidHistory("unexpected rejection-code text in history")
        if event["exceptionType"] is not None and not re.fullmatch(
                r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*){0,15}", event["exceptionType"]):
            raise InvalidHistory("unexpected exception-type text in history")
    if not all(type(value) is str and ISO_TIME.fullmatch(value) for value in timestamps):
        raise InvalidHistory("unexpected timestamp text in history")
    return history


def expected_property(scenario):
    return "inventory_reservation_history" if scenario == "inventory_reserve" else "checkout_retry_history"


def create(history_path, bundle_dir, *, source_kind, exclusive_scope, max_states=100_000,
           budget_ms=2_000):
    if source_kind not in ("captured_test_execution", "synthetic_mutation"):
        raise InvalidHistory("unsupported source kind")
    raw = bounded_read(history_path)
    history = checked_history(raw)
    if not exclusive_scope:
        raise InvalidHistory("exclusive target scope declaration required for replay bundle")
    result = check(history, exclusive_scope=True, max_states=max_states, budget_ms=budget_ms)
    bundle_dir = Path(bundle_dir)
    bundle_dir.mkdir(parents=True, exist_ok=False)
    (bundle_dir / "history.json").write_bytes(raw)
    manifest = {
        "schema_version": 1,
        "source_kind": source_kind,
        "history_file": "history.json",
        "history_sha256": digest(raw),
        "checker_file": "history_checker.py",
        "checker_sha256": digest(CHECKER.read_bytes()),
        "checker_contract": "m19-history-checker-v1",
        "scenario_id": history["plan"]["scenarioId"],
        "run_id": history["plan"]["runId"],
        "seed": history["plan"]["seed"],
        "plan": history["plan"],
        "provenance": history["provenance"],
        "property": expected_property(history["plan"]["scenarioId"]),
        "assumptions": {"exclusive_target_scope": True,
                        "no_unrecorded_writers": True,
                        "logical_identity_matches_calls": True},
        "budgets": {"max_states": max_states, "budget_ms": budget_ms},
        "original_result": result,
    }
    (bundle_dir / "manifest.json").write_bytes(encoded(manifest))
    return result


def load_bundle(bundle_dir):
    bundle_dir = Path(bundle_dir)
    manifest = json.loads(bounded_read(bundle_dir / "manifest.json"))
    required = {"schema_version", "source_kind", "history_file", "history_sha256",
                "checker_file", "checker_sha256", "checker_contract", "scenario_id", "run_id",
                "seed", "plan", "provenance", "property", "assumptions", "budgets", "original_result"}
    if set(manifest) != required or manifest["schema_version"] != 1:
        raise InvalidHistory("unsupported replay manifest")
    if manifest["history_file"] != "history.json" or manifest["checker_file"] != "history_checker.py":
        raise InvalidHistory("unsupported bundle file name")
    if manifest["checker_contract"] != "m19-history-checker-v1" or manifest["checker_sha256"] != digest(CHECKER.read_bytes()):
        raise InvalidHistory("checker implementation changed")
    if manifest["source_kind"] not in ("captured_test_execution", "synthetic_mutation"):
        raise InvalidHistory("unsupported source kind")
    if manifest["assumptions"] != {"exclusive_target_scope": True, "no_unrecorded_writers": True,
                                    "logical_identity_matches_calls": True}:
        raise InvalidHistory("unsupported or missing scope assumptions")
    raw = bounded_read(bundle_dir / "history.json")
    if digest(raw) != manifest["history_sha256"]:
        raise InvalidHistory("original history hash mismatch")
    history = checked_history(raw)
    plan = history["plan"]
    if (manifest["scenario_id"] != plan["scenarioId"] or manifest["run_id"] != plan["runId"]
            or manifest["seed"] != plan["seed"] or manifest["plan"] != plan
            or manifest["provenance"] != history["provenance"]
            or manifest["property"] != expected_property(plan["scenarioId"])):
        raise InvalidHistory("manifest does not match history")
    if set(manifest["budgets"]) != {"max_states", "budget_ms"}:
        raise InvalidHistory("invalid search budgets")
    return manifest, history


def recheck(bundle_dir):
    manifest, history = load_bundle(bundle_dir)
    result = check(history, exclusive_scope=True, **manifest["budgets"])
    if result != manifest["original_result"]:
        raise InvalidHistory("offline checker result differs from recorded verdict or diagnostics")
    return result


def candidates(history):
    plan = history["plan"]
    actors, steps = plan["actorCount"], plan["operationsPerActor"]
    for kind, allowed in (("trim_last_step_each_actor", steps > 1),
                          ("trim_last_actor", actors > 2)):
        if not allowed:
            continue
        candidate = copy.deepcopy(history)
        target = (lambda op: op["step"] < steps - 1) if kind.startswith("trim_last_step") else (
            lambda op: op["actorId"] < actors - 1)
        kept = [(op, obs) for op, obs in zip(plan["operations"], history["observations"]) if target(op)]
        candidate["plan"]["operations"] = [op for op, _ in kept]
        candidate["observations"] = [obs for _, obs in kept]
        candidate["plan"]["operationsPerActor" if kind.startswith("trim_last_step") else "actorCount"] -= 1
        size = len(kept)
        candidate["plannedCount"] = candidate["invokedCount"] = candidate["completedCount"] = size
        yield kind, candidate


def reduce_failure(bundle_dir, *, max_attempts=32, budget_ms=10_000):
    manifest, original = load_bundle(bundle_dir)
    if recheck(bundle_dir)["verdict"] != "FAIL":
        raise InvalidHistory("reduction requires an exhaustively checked FAIL")
    if not (1 <= max_attempts <= 128 and 1 <= budget_ms <= 60_000):
        raise InvalidHistory("invalid reduction bounds")
    current = original
    steps = []
    attempts = 0
    deadline = time.monotonic() + budget_ms / 1000
    while attempts < max_attempts and time.monotonic() < deadline:
        accepted = False
        for kind, candidate in candidates(current):
            if attempts >= max_attempts or time.monotonic() >= deadline:
                break
            attempts += 1
            try:
                validate(candidate)
                verdict = check(candidate, exclusive_scope=True, **manifest["budgets"])
            except (InvalidHistory, ValueError) as exc:
                steps.append({"attempt": attempts, "change": kind, "decision": "REJECTED",
                              "reason": str(exc)})
                continue
            decision = "ACCEPTED" if verdict["verdict"] == "FAIL" else "REJECTED"
            steps.append({"attempt": attempts, "change": kind, "decision": decision,
                          "candidate_sha256": digest(encoded(candidate)), "checker_result": verdict})
            if decision == "ACCEPTED":
                current = candidate
                accepted = True
                break
        if not accepted:
            break
    local_minimal = True
    for _, candidate in candidates(current):
        if time.monotonic() >= deadline:
            local_minimal = False
            break
        try:
            validate(candidate)
            if check(candidate, exclusive_scope=True, **manifest["budgets"])["verdict"] != "PASS":
                local_minimal = False
        except InvalidHistory:
            pass
    if (attempts >= max_attempts and any(True for _ in candidates(current))) or time.monotonic() >= deadline:
        local_minimal = False
    record = {"schema_version": 1, "kind": "derived_analytical_counterexample",
              "original_sha256": manifest["history_sha256"],
              "reduced_sha256": digest(encoded(current)),
              "property": manifest["property"], "assumptions": manifest["assumptions"],
              "initial_stock": original["plan"]["initialStock"],
              "original_operations": original["plannedCount"],
              "reduced_operations": current["plannedCount"],
              "attempts": attempts, "locally_minimal_under_supported_deletions": local_minimal,
              "steps": steps, "final_result": check(current, exclusive_scope=True, **manifest["budgets"])}
    if record["final_result"]["verdict"] != "FAIL":
        raise InvalidHistory("reduction lost original failure")
    bundle_dir = Path(bundle_dir)
    for name, raw in (("reduced_history.json", encoded(current)), ("reduction.json", encoded(record))):
        with (bundle_dir / name).open("xb") as output:
            output.write(raw)
    return record


def rerun(bundle_dir, attempts):
    manifest, original = load_bundle(bundle_dir)
    if not (1 <= attempts <= 3):
        raise InvalidHistory("rerun attempts must be 1–3")
    if manifest["source_kind"] != "captured_test_execution":
        raise InvalidHistory("live rerun requires an actual captured test execution")
    scenario = manifest["scenario_id"]
    expected = {"inventory_reserve": (17, 1, 2, 2, 20_000),
                "checkout_retry": (33, 1, 3, 2, 20_000)}[scenario]
    plan = original["plan"]
    if tuple(plan[k] for k in ("seed", "initialStock", "actorCount", "operationsPerActor", "timeoutMillis")) != expected:
        raise InvalidHistory("existing M18 test does not support this input plan")
    output_dir = Path(bundle_dir) / "attempts"
    output_dir.mkdir(exist_ok=False)
    report = []
    for number in range(1, attempts + 1):
        env = os.environ.copy()
        env.update(TESTCONTAINERS_RYUK_DISABLED="true", TESTCONTAINERS_CHECKS_DISABLE="true")
        subprocess.run(["./gradlew", ":test", "--tests", "com.commercecore.consistencylab.M18WorkloadTest",
                        "--rerun-tasks", "--console=plain"], check=True, env=env, timeout=180,
                       stdout=subprocess.DEVNULL)
        source = Path("build/consistency-lab/m18") / f"{scenario}.json"
        raw = bounded_read(source)
        attempt = json.loads(raw)
        validate(attempt)
        attempt_path = output_dir / f"{number:02d}.json"
        attempt_path.write_bytes(raw)
        same_plan = all(attempt["plan"][k] == plan[k] for k in
                        ("scenarioId", "seed", "initialStock", "actorCount", "operationsPerActor", "timeoutMillis", "operations"))
        result = check(attempt, exclusive_scope=True, **manifest["budgets"])
        report.append({"attempt": number, "history_file": attempt_path.name,
                       "sha256": digest(raw), "same_input_plan_except_run_id": same_plan,
                       "complete": attempt["complete"], "result": result,
                       "observed_intervals": [{"operation_id": o["operationId"],
                                               "invoked_nanos": o["invokedNanos"],
                                               "completed_nanos": o["completedNanos"]}
                                              for o in attempt["observations"]]})
    target = manifest["original_result"]["verdict"]
    status = ("NO_TARGET_FAILURE" if target != "FAIL" else
              "REPRODUCED" if any(x["same_input_plan_except_run_id"] and
                  x["complete"] and x["result"]["verdict"] == "FAIL" for x in report)
              else "NOT_REPRODUCED")
    summary = {"schema_version": 1, "original_sha256": manifest["history_sha256"],
               "target_verdict": target, "status": status, "attempt_count": attempts,
               "attempts": report}
    (output_dir / "report.json").write_bytes(encoded(summary))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create_cmd = sub.add_parser("create")
    create_cmd.add_argument("history")
    create_cmd.add_argument("bundle")
    create_cmd.add_argument("--source-kind", required=True,
                            choices=("captured_test_execution", "synthetic_mutation"))
    create_cmd.add_argument("--exclusive-scope", action="store_true")
    create_cmd.add_argument("--max-states", type=int, default=100_000)
    create_cmd.add_argument("--budget-ms", type=int, default=2_000)
    for command in ("recheck", "reduce", "rerun"):
        item = sub.add_parser(command)
        item.add_argument("bundle")
        if command == "reduce":
            item.add_argument("--max-attempts", type=int, default=32)
            item.add_argument("--budget-ms", type=int, default=10_000)
        if command == "rerun":
            item.add_argument("--attempts", type=int, default=1)
    args = parser.parse_args()
    try:
        if args.command == "create":
            result = create(args.history, args.bundle, source_kind=args.source_kind,
                            exclusive_scope=args.exclusive_scope, max_states=args.max_states,
                            budget_ms=args.budget_ms)
        elif args.command == "recheck":
            result = recheck(args.bundle)
        elif args.command == "reduce":
            result = reduce_failure(args.bundle, max_attempts=args.max_attempts,
                                    budget_ms=args.budget_ms)
        else:
            result = rerun(args.bundle, args.attempts)
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError,
            subprocess.SubprocessError) as exc:
        print(f"M20 error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
