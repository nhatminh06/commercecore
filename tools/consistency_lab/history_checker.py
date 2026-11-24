"""Independent bounded sequential-model checker for M18 operation histories."""

import argparse
import json
import os
import sys
import time
import uuid


class InvalidHistory(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidHistory(message)


def fields(value, expected, where):
    require(type(value) is dict and set(value) == set(expected), f"{where}: invalid fields")


PLAN = ("schemaVersion", "scenarioId", "runId", "seed", "initialStock", "actorCount",
        "operationsPerActor", "timeoutMillis", "startConstraint", "operations")
OP = ("operationId", "actorId", "step", "type", "logicalRequestId", "quantity")
OBS = ("operationId", "state", "outcome", "resultId", "rejectionCode", "exceptionType",
       "invokedNanos", "completedNanos", "invokedAt", "completedAt")
HISTORY = ("schemaVersion", "plan", "provenance", "clockSource", "startedAt", "finishedAt",
           "quiescent", "complete", "quiescenceEvidence", "plannedCount", "invokedCount",
           "completedCount", "observations")


def integer(value, low, high, label):
    require(type(value) is int and low <= value <= high, f"{label}: out of bounds")


def validate(h):
    fields(h, HISTORY, "history")
    require(h["schemaVersion"] == 1 and type(h["schemaVersion"]) is int, "unsupported history version")
    p = h["plan"]
    fields(p, PLAN, "plan")
    require(p["schemaVersion"] == 1 and type(p["schemaVersion"]) is int, "unsupported plan version")
    require(p["scenarioId"] in ("inventory_reserve", "checkout_retry"), "unsupported scenario")
    try:
        require(str(uuid.UUID(p["runId"])) == p["runId"], "noncanonical run ID")
    except (TypeError, ValueError, AttributeError) as exc:
        raise InvalidHistory("invalid run ID") from exc
    integer(p["seed"], -(2**63), 2**63 - 1, "seed")
    integer(p["initialStock"], 0, 32, "initial stock")
    integer(p["actorCount"], 2, 8, "actors")
    integer(p["operationsPerActor"], 1, 4, "operations per actor")
    integer(p["timeoutMillis"], 100, 60_000, "timeout")
    require(p["actorCount"] * p["operationsPerActor"] <= 32, "too many operations")
    require(p["startConstraint"] ==
            "first operation starts after all actors are ready; each actor is sequential",
            "unsupported ordering constraint")
    require(type(p["operations"]) is list and
            len(p["operations"]) == p["actorCount"] * p["operationsPerActor"], "plan coverage")
    require(type(h["observations"]) is list and len(h["observations"]) == len(p["operations"]),
            "observation coverage")
    ids, pairs = set(), set()
    invoked = completed = 0
    for op, obs in zip(p["operations"], h["observations"]):
        fields(op, OP, "operation")
        fields(obs, OBS, "observation")
        integer(op["actorId"], 0, p["actorCount"] - 1, "actor ID")
        integer(op["step"], 0, p["operationsPerActor"] - 1, "step")
        require(op["type"] == p["scenarioId"] and op["quantity"] == 1 and
                type(op["quantity"]) is int, "unsupported operation")
        expected_id = f'{p["scenarioId"]}/actor-{op["actorId"]}/step-{op["step"]}'
        require(op["operationId"] == expected_id and expected_id not in ids, "operation identity")
        ids.add(expected_id)
        pairs.add((op["actorId"], op["step"]))
        expected_logical = "shared-checkout-key" if p["scenarioId"] == "checkout_retry" else expected_id
        require(op["logicalRequestId"] == expected_logical, "logical request identity")
        require(obs["operationId"] == expected_id, "observation identity")
        state = obs["state"]
        require(state in ("NOT_INVOKED", "UNRESOLVED", "COMPLETED"), "observation state")
        if state == "NOT_INVOKED":
            require(all(obs[k] is None for k in OBS[2:]), "fabricated invocation")
        else:
            invoked += 1
            integer(obs["invokedNanos"], 0, 2**63 - 1, "invocation time")
            require(type(obs["invokedAt"]) is str and obs["invokedAt"], "missing invocation clock context")
            if state == "UNRESOLVED":
                require(all(obs[k] is None for k in ("outcome", "resultId", "rejectionCode",
                                                       "exceptionType", "completedNanos", "completedAt")),
                        "fabricated completion")
            else:
                completed += 1
                integer(obs["completedNanos"], obs["invokedNanos"], 2**63 - 1, "completion time")
                require(type(obs["completedAt"]) is str and obs["completedAt"], "missing completion clock context")
                require(obs["outcome"] in ("SUCCESS", "REJECTED", "EXCEPTION"), "invalid outcome")
                payloads = (obs["resultId"], obs["rejectionCode"], obs["exceptionType"])
                index = {"SUCCESS": 0, "REJECTED": 1, "EXCEPTION": 2}[obs["outcome"]]
                require(type(payloads[index]) is str and bool(payloads[index]) and
                        all(x is None for j, x in enumerate(payloads) if j != index), "invalid result payload")
                if obs["outcome"] == "SUCCESS":
                    try:
                        require(str(uuid.UUID(obs["resultId"])) == obs["resultId"], "invalid result ID")
                    except (TypeError, ValueError) as exc:
                        raise InvalidHistory("invalid result ID") from exc
    require(len(pairs) == len(p["operations"]), "missing actor step")
    for key in ("plannedCount", "invokedCount", "completedCount"):
        integer(h[key], 0, 32, key)
    require((h["plannedCount"], h["invokedCount"], h["completedCount"]) ==
            (len(ids), invoked, completed), "event counts disagree")
    require(type(h["quiescent"]) is bool and type(h["complete"]) is bool and
            h["complete"] == (h["quiescent"] and completed == len(ids)), "invalid completeness claim")
    require(h["quiescenceEvidence"] == ("all actor tasks terminated before history freeze" if h["quiescent"]
            else "actor termination unconfirmed"), "invalid quiescence evidence")
    require(h["clockSource"] == "System.nanoTime monotonic within test JVM" and
            h["provenance"] == "test-jvm/spring-service/disposable-postgresql", "unsupported provenance")
    require(all(type(h[k]) is str and h[k] for k in ("startedAt", "finishedAt")), "missing wall-clock context")
    for actor in range(p["actorCount"]):
        sequence = [(op["step"], obs) for op, obs in zip(p["operations"], h["observations"])
                    if op["actorId"] == actor]
        sequence.sort()
        for (_, before), (_, after) in zip(sequence, sequence[1:]):
            require(before["state"] != "NOT_INVOKED" or after["state"] == "NOT_INVOKED",
                    "actor invoked after missing step")
            if after["state"] != "NOT_INVOKED":
                require(before["state"] == "COMPLETED" and
                        before["completedNanos"] <= after["invokedNanos"], "actor order or interval")
    return p


def check(history, *, exclusive_scope=False, max_states=100_000, budget_ms=2_000):
    p = validate(history)
    integer(max_states, 1, 1_000_000, "state budget")
    integer(budget_ms, 1, 60_000, "time budget")
    property_id = "inventory_reservation_history" if p["scenarioId"] == "inventory_reserve" else "checkout_retry_history"
    base = {"property": property_id, "run_id": p["runId"], "scope": "M18 observed service calls",
            "checked_operations": history["completedCount"]}

    def result(verdict, reason, witness=None, explored=0):
        return {**base, "verdict": verdict, "reason": reason, "witness": witness, "explored_states": explored}

    if not history["complete"]:
        return result("INCONCLUSIVE", "unresolved, missing, or nonquiescent operations")
    if not exclusive_scope:
        return result("INCONCLUSIVE", "exclusive target scope and absence of unaccounted writers not established")
    ops = p["operations"]
    obs = history["observations"]
    if any(o["outcome"] == "EXCEPTION" for o in obs):
        return result("INCONCLUSIVE", "exception outcome has no supported sequential model")
    if p["scenarioId"] == "checkout_retry" and any(o["outcome"] == "REJECTED" and
        o["rejectionCode"] != "insufficient_stock" for o in obs):
        return result("INCONCLUSIVE", "checkout rejection code is outside the model")
    if p["scenarioId"] == "inventory_reserve" and any(o["outcome"] == "REJECTED" and
        o["rejectionCode"] != "insufficient_stock" for o in obs):
        return result("INCONCLUSIVE", "reservation rejection code is outside the model")
    predecessors = []
    for i, (op, event) in enumerate(zip(ops, obs)):
        mask = 0
        for j, (other, prior) in enumerate(zip(ops, obs)):
            if i == j:
                continue
            if (other["actorId"] == op["actorId"] and other["step"] < op["step"] or
                prior["completedNanos"] < event["invokedNanos"]):
                mask |= 1 << j
        predecessors.append(mask)
    deadline = time.monotonic() + budget_ms / 1000
    explored = 0
    seen = set()
    limit_hit = False

    def search(mask, stock, order, reservation_ids, witness):
        nonlocal explored, limit_hit
        if time.monotonic() >= deadline or explored >= max_states:
            limit_hit = True
            return None
        if mask == (1 << len(ops)) - 1:
            return witness
        key = (mask, stock, order, reservation_ids)
        if key in seen:
            return None
        seen.add(key)
        explored += 1
        for i, (op, event) in enumerate(zip(ops, obs)):
            bit = 1 << i
            if mask & bit or predecessors[i] & ~mask:
                continue
            next_stock, next_order, next_ids = stock, order, reservation_ids
            if p["scenarioId"] == "inventory_reserve":
                expected = "SUCCESS" if stock >= op["quantity"] else "REJECTED"
                if event["outcome"] != expected:
                    continue
                if expected == "SUCCESS":
                    if event["resultId"] in reservation_ids:
                        continue
                    next_stock -= op["quantity"]
                    next_ids = reservation_ids | {event["resultId"]}
            else:
                if order is not None:
                    if event["outcome"] != "SUCCESS" or event["resultId"] != order:
                        continue
                elif stock < 1:
                    if event["outcome"] != "REJECTED":
                        continue
                else:
                    if event["outcome"] != "SUCCESS":
                        continue
                    next_stock -= 1
                    next_order = event["resultId"]
            answer = search(mask | bit, next_stock, next_order, next_ids, witness + [op["operationId"]])
            if answer is not None:
                return answer
        return None

    witness = search(0, p["initialStock"], None, frozenset(), [])
    if witness is not None:
        return result("PASS", "a legal sequential ordering exists under the declared exclusive scope", witness, explored)
    if limit_hit:
        return result("INCONCLUSIVE", "search budget exhausted before exhaustive contradiction", None, explored)
    return result("FAIL", "exhaustive search found no legal ordering for the observed outcomes and intervals", None, explored)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("history")
    parser.add_argument("--exclusive-scope", action="store_true",
                        help="attest target SKU/key and initial state had no unrecorded writers")
    parser.add_argument("--max-states", type=int, default=100_000)
    parser.add_argument("--budget-ms", type=int, default=2_000)
    args = parser.parse_args()
    try:
        if os.path.getsize(args.history) > 262_144:
            raise InvalidHistory("history file exceeds 256 KiB")
        with open(args.history, encoding="utf-8") as source:
            result = check(json.load(source), exclusive_scope=args.exclusive_scope,
                           max_states=args.max_states, budget_ms=args.budget_ms)
    except (OSError, json.JSONDecodeError, InvalidHistory, TypeError, KeyError, ValueError) as exc:
        print(f"invalid history: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
