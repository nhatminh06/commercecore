"""Offline bundle integrity and deletion-minimization qualification."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

from history_checker import InvalidHistory, check, validate
from replay import create, load_bundle, recheck, reduce_failure
from test_history_checker import OTHER, history


def redundant_failure(scenario="inventory_reserve"):
    case = history(scenario, stock=1, outcomes=("SUCCESS", "SUCCESS"))
    if scenario == "checkout_retry":
        case["observations"][1]["resultId"] = OTHER
    plan = case["plan"]
    plan["operationsPerActor"] = 2
    originals = list(zip(plan["operations"], case["observations"]))
    expanded = []
    for op, obs in originals:
        later_op = copy.deepcopy(op)
        later_op["step"] = 1
        later_op["operationId"] = op["operationId"].replace("step-0", "step-1")
        if scenario == "inventory_reserve":
            later_op["logicalRequestId"] = later_op["operationId"]
        later_obs = copy.deepcopy(obs)
        later_obs.update(operationId=later_op["operationId"], outcome="REJECTED" if scenario == "inventory_reserve" else "SUCCESS",
                         resultId=None if scenario == "inventory_reserve" else obs["resultId"],
                         rejectionCode="insufficient_stock" if scenario == "inventory_reserve" else None,
                         invokedNanos=10 + op["actorId"], completedNanos=12 + op["actorId"])
        expanded.extend(((op, obs), (later_op, later_obs)))
    plan["operations"] = [op for op, _ in expanded]
    case["observations"] = [obs for _, obs in expanded]
    case["plannedCount"] = case["invokedCount"] = case["completedCount"] = 4
    validate(case)
    return case


class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def bundle(self, case, *, kind="synthetic_mutation", max_states=100_000):
        source = self.root / "source.json"
        source.write_text(json.dumps(case))
        folder = self.root / "bundle"
        create(source, folder, source_kind=kind, exclusive_scope=True, max_states=max_states)
        return folder

    def test_inventory_replay_and_minimal_reduction_preserve_original(self):
        original = redundant_failure()
        folder = self.bundle(original)
        before = (folder / "history.json").read_bytes()
        self.assertEqual(recheck(folder)["verdict"], "FAIL")
        reduction = reduce_failure(folder)
        self.assertEqual((reduction["original_operations"], reduction["reduced_operations"]), (4, 2))
        self.assertTrue(reduction["locally_minimal_under_supported_deletions"])
        self.assertEqual(reduction["final_result"]["verdict"], "FAIL")
        self.assertEqual((folder / "history.json").read_bytes(), before)
        reduced = json.loads((folder / "reduced_history.json").read_text())
        self.assertEqual([o["operationId"] for o in reduced["plan"]["operations"]],
                         [original["plan"]["operations"][i]["operationId"] for i in (0, 2)])
        self.assertEqual(reduced["plan"]["initialStock"], 1)
        self.assertEqual(reduced["observations"], [original["observations"][i] for i in (0, 2)])

    def test_checkout_conflict_reduces_without_changing_identities(self):
        folder = self.bundle(redundant_failure("checkout_retry"))
        result = reduce_failure(folder)
        self.assertEqual(result["reduced_operations"], 2)
        self.assertEqual(result["final_result"]["verdict"], "FAIL")

    def test_valid_overlap_is_not_reduced(self):
        case = history(intervals=((1, 6), (2, 4)), outcomes=("REJECTED", "SUCCESS"))
        folder = self.bundle(case)
        self.assertEqual(recheck(folder)["verdict"], "PASS")
        with self.assertRaisesRegex(InvalidHistory, "requires.*FAIL"):
            reduce_failure(folder)

    def test_incomplete_and_budget_exhaustion(self):
        case = history()
        case["complete"] = False
        case["observations"][1].update(state="UNRESOLVED", outcome=None, rejectionCode=None,
                                        completedNanos=None, completedAt=None)
        case["completedCount"] = 1
        with self.assertRaisesRegex(InvalidHistory, "complete"):
            self.bundle(case)
        folder = self.bundle(redundant_failure(), max_states=1)
        self.assertEqual(recheck(folder)["verdict"], "INCONCLUSIVE")
        with self.assertRaisesRegex(InvalidHistory, "requires.*FAIL"):
            reduce_failure(folder)

    def test_tampered_history_and_manifest_fail_closed(self):
        folder = self.bundle(redundant_failure())
        (folder / "history.json").write_bytes((folder / "history.json").read_bytes() + b" ")
        with self.assertRaisesRegex(InvalidHistory, "hash mismatch"):
            recheck(folder)
        folder2 = self.root / "bundle2"
        source = self.root / "source.json"
        create(source, folder2, source_kind="synthetic_mutation", exclusive_scope=True)
        manifest = json.loads((folder2 / "manifest.json").read_text())
        manifest["property"] = "other_property"
        (folder2 / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(InvalidHistory, "manifest does not match"):
            recheck(folder2)

    def test_malformed_bundle_and_deterministic_recheck(self):
        folder = self.bundle(redundant_failure())
        self.assertEqual(recheck(folder), recheck(folder))
        manifest = json.loads((folder / "manifest.json").read_text())
        manifest["schema_version"] = 99
        (folder / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(InvalidHistory, "unsupported replay manifest"):
            load_bundle(folder)

    def test_unexpected_free_text_is_rejected_before_copy(self):
        case = history()
        case["observations"][1]["rejectionCode"] = "unexpected value"
        with self.assertRaisesRegex(InvalidHistory, "rejection-code"):
            self.bundle(case)
        self.assertFalse((self.root / "bundle").exists())


if __name__ == "__main__":
    unittest.main()
