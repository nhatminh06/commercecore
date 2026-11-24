"""Generate a self-contained, read-only M18–M20 execution-history debugger."""

import argparse
import base64
import json
from pathlib import Path
import sys

from history_checker import InvalidHistory, check
from replay import bounded_read, checked_history, digest, encoded, load_bundle, recheck


HERE = Path(__file__).resolve().parent


def evidence(source):
    source = Path(source)
    if source.is_file():
        history = checked_history(bounded_read(source), require_complete=False)
        return {"history": history, "result": check(history), "source": "unbundled_history",
                "integrity": "M18 schema validated; no bundle hash", "manifest": None, "reduced": None}

    manifest, history = load_bundle(source)
    result = recheck(source)
    reduced = None
    reduction_path = source / "reduction.json"
    reduced_path = source / "reduced_history.json"
    if reduction_path.exists() != reduced_path.exists():
        raise InvalidHistory("partial reduction files")
    if reduction_path.exists():
        reduction = json.loads(bounded_read(reduction_path))
        raw = bounded_read(reduced_path)
        candidate = checked_history(raw)
        original_pairs = {op["operationId"]: (op, obs) for op, obs in
                          zip(history["plan"]["operations"], history["observations"])}
        ids = [op["operationId"] for op in candidate["plan"]["operations"]]
        if (set(reduction) != {"schema_version", "kind", "original_sha256", "reduced_sha256",
                               "property", "assumptions", "initial_stock", "original_operations",
                               "reduced_operations", "attempts", "locally_minimal_under_supported_deletions",
                               "steps", "final_result"}
                or reduction["schema_version"] != 1
                or reduction["kind"] != "derived_analytical_counterexample"
                or reduction["original_sha256"] != manifest["history_sha256"]
                or reduction["reduced_sha256"] != digest(raw)
                or reduction["property"] != manifest["property"]
                or reduction["assumptions"] != manifest["assumptions"]
                or reduction["initial_stock"] != history["plan"]["initialStock"]
                or reduction["original_operations"] != history["plannedCount"]
                or reduction["reduced_operations"] != candidate["plannedCount"]
                or result["verdict"] != "FAIL"
                or not set(ids).issubset(original_pairs)
                or any(original_pairs[op["operationId"]] != (op, obs) for op, obs in
                       zip(candidate["plan"]["operations"], candidate["observations"]))
                or any(candidate["plan"][key] != history["plan"][key] for key in
                       ("scenarioId", "runId", "seed", "initialStock", "timeoutMillis", "startConstraint"))
                or reduction["final_result"] != check(candidate, exclusive_scope=True, **manifest["budgets"])
                or reduction["final_result"]["verdict"] != "FAIL"):
            raise InvalidHistory("reduction does not match verified original and checker result")
        reduced = {"history": candidate, "result": reduction["final_result"],
                   "retained": ids, "removed": [op["operationId"] for op in
                   history["plan"]["operations"] if op["operationId"] not in ids],
                   "locally_minimal": reduction["locally_minimal_under_supported_deletions"]}
    return {"history": history, "result": result, "source": manifest["source_kind"],
            "integrity": "SHA-256 and M19 recheck verified (not authentication)",
            "manifest": {key: manifest[key] for key in ("schema_version", "checker_contract",
                "history_sha256", "checker_sha256", "assumptions", "budgets")}, "reduced": reduced}


def render(source):
    payload = base64.b64encode(encoded(evidence(source))).decode("ascii")
    template = (HERE / "debugger.html").read_text(encoding="utf-8")
    return template.replace("__EVIDENCE_BASE64__", payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="M18 history JSON or M20 bundle directory")
    parser.add_argument("output", help="new standalone HTML report")
    args = parser.parse_args()
    try:
        html = render(args.source)
        with Path(args.output).open("x", encoding="utf-8") as output:
            output.write(html)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"M21 error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
