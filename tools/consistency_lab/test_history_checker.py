"""Mutation tests for the independent M18 history checker."""

import copy
import unittest

from history_checker import InvalidHistory, check, validate


RUN = "00000000-0000-4000-8000-000000000019"
ORDER = "10000000-0000-4000-8000-000000000019"
OTHER = "20000000-0000-4000-8000-000000000019"


def history(scenario="inventory_reserve", stock=1, intervals=((1, 4), (2, 5)),
            outcomes=("SUCCESS", "REJECTED")):
    operations, observations = [], []
    for actor, ((start, end), outcome) in enumerate(zip(intervals, outcomes)):
        op_id = f"{scenario}/actor-{actor}/step-0"
        operations.append({"operationId": op_id, "actorId": actor, "step": 0,
                           "type": scenario, "logicalRequestId":
                           "shared-checkout-key" if scenario == "checkout_retry" else op_id,
                           "quantity": 1})
        observations.append({"operationId": op_id, "state": "COMPLETED", "outcome": outcome,
                             "resultId": (ORDER if scenario == "checkout_retry" else OTHER)
                             if outcome == "SUCCESS" else None,
                             "rejectionCode": "insufficient_stock" if outcome == "REJECTED" else None,
                             "exceptionType": None, "invokedNanos": start, "completedNanos": end,
                             "invokedAt": "2026-01-01T00:00:00Z",
                             "completedAt": "2026-01-01T00:00:00Z"})
    plan = {"schemaVersion": 1, "scenarioId": scenario, "runId": RUN, "seed": 3,
            "initialStock": stock, "actorCount": 2, "operationsPerActor": 1,
            "timeoutMillis": 1000,
            "startConstraint": "first operation starts after all actors are ready; each actor is sequential",
            "operations": operations}
    return {"schemaVersion": 1, "plan": plan,
            "provenance": "test-jvm/spring-service/disposable-postgresql",
            "clockSource": "System.nanoTime monotonic within test JVM",
            "startedAt": "2026-01-01T00:00:00Z", "finishedAt": "2026-01-01T00:00:01Z",
            "quiescent": True, "complete": True,
            "quiescenceEvidence": "all actor tasks terminated before history freeze",
            "plannedCount": 2, "invokedCount": 2, "completedCount": 2,
            "observations": observations}


class HistoryCheckerTest(unittest.TestCase):
    def test_valid_sequential_and_overlapping_inventory(self):
        sequential = history(intervals=((1, 2), (3, 4)))
        self.assertEqual(check(sequential, exclusive_scope=True)["verdict"], "PASS")
        overlapping = history(intervals=((1, 6), (2, 4)), outcomes=("REJECTED", "SUCCESS"))
        self.assertEqual(check(overlapping, exclusive_scope=True)["verdict"], "PASS")
        self.assertEqual(check(overlapping, exclusive_scope=True)["witness"][0],
                         "inventory_reserve/actor-1/step-0")

    def test_oversell_and_impossible_rejection(self):
        oversell = history(outcomes=("SUCCESS", "SUCCESS"))
        self.assertEqual(check(oversell, exclusive_scope=True)["verdict"], "FAIL")
        premature = history(intervals=((1, 2), (3, 4)), outcomes=("REJECTED", "SUCCESS"))
        self.assertEqual(check(premature, exclusive_scope=True)["verdict"], "FAIL")
        duplicate_ids = history(stock=2, outcomes=("SUCCESS", "SUCCESS"))
        self.assertEqual(check(duplicate_ids, exclusive_scope=True)["verdict"], "FAIL")

    def test_checkout_identity_and_rejection(self):
        good = history("checkout_retry", outcomes=("SUCCESS", "SUCCESS"))
        self.assertEqual(check(good, exclusive_scope=True)["verdict"], "PASS")
        bad = copy.deepcopy(good)
        bad["observations"][1]["resultId"] = OTHER
        self.assertEqual(check(bad, exclusive_scope=True)["verdict"], "FAIL")
        denied = history("checkout_retry", stock=0, outcomes=("REJECTED", "REJECTED"))
        self.assertEqual(check(denied, exclusive_scope=True)["verdict"], "PASS")
        impossible = history("checkout_retry", stock=1, outcomes=("REJECTED", "REJECTED"))
        self.assertEqual(check(impossible, exclusive_scope=True)["verdict"], "FAIL")

    def test_incomplete_and_unaccounted_writers(self):
        item = history()
        self.assertEqual(check(item)["verdict"], "INCONCLUSIVE")
        item["observations"][1].update(state="UNRESOLVED", outcome=None,
            rejectionCode=None, completedNanos=None, completedAt=None)
        item.update(complete=False, completedCount=1)
        self.assertEqual(check(item, exclusive_scope=True)["verdict"], "INCONCLUSIVE")
        item["observations"][1].update(state="NOT_INVOKED", invokedNanos=None, invokedAt=None)
        item["invokedCount"] = 1
        self.assertEqual(check(item, exclusive_scope=True)["verdict"], "INCONCLUSIVE")

    def test_malformed_identity_intervals_and_counts(self):
        for change in (lambda x: x["observations"][0].update(invokedNanos=10),
                       lambda x: x["plan"]["operations"][1].update(operationId="bad"),
                       lambda x: x.update(completedCount=1),
                       lambda x: x.update(complete=False),
                       lambda x: x["observations"][0].update(invokedNanos=None)):
            item = history()
            change(item)
            with self.subTest(item=item), self.assertRaises(InvalidHistory):
                validate(item)

    def test_budget_exhaustion_is_inconclusive(self):
        item = history(intervals=((1, 2), (3, 4)))
        self.assertEqual(check(item, exclusive_scope=True, max_states=1)["verdict"], "INCONCLUSIVE")

    def test_deterministic_result(self):
        item = history()
        self.assertEqual(check(item, exclusive_scope=True), check(item, exclusive_scope=True))


if __name__ == "__main__":
    unittest.main()
