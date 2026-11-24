"""Offline debugger evidence and output qualification."""

import json
from pathlib import Path
import tempfile
import unittest

from debugger import evidence, render
from history_checker import InvalidHistory
from replay import create, reduce_failure
from test_history_checker import history
from test_replay import redundant_failure


class DebuggerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def source(self, case):
        path = self.root / "source.json"
        path.write_text(json.dumps(case), encoding="utf-8")
        return path

    def bundle(self, case):
        path = self.source(case)
        folder = self.root / "bundle"
        create(path, folder, source_kind="synthetic_mutation", exclusive_scope=True)
        return folder

    def test_unbundled_overlap_and_precedence(self):
        case = history(intervals=((1, 6), (2, 4)), outcomes=("REJECTED", "SUCCESS"))
        item = evidence(self.source(case))
        self.assertEqual(item["result"]["verdict"], "INCONCLUSIVE")
        self.assertIsNone(item["manifest"])
        self.assertEqual(render(self.root / "source.json"), render(self.root / "source.json"))
        folder = self.bundle(case)
        self.assertEqual(evidence(folder)["result"]["witness"][0], case["plan"]["operations"][1]["operationId"])
        self.assertIn("Mandatory observed ordering", render(folder))

    def test_incomplete_and_not_invoked_remain_inconclusive(self):
        case = history()
        case["observations"][1].update(state="UNRESOLVED", outcome=None, rejectionCode=None,
            completedNanos=None, completedAt=None)
        case.update(complete=False, completedCount=1)
        self.assertEqual(evidence(self.source(case))["result"]["verdict"], "INCONCLUSIVE")
        case["observations"][1].update(state="NOT_INVOKED", invokedNanos=None, invokedAt=None)
        case["invokedCount"] = 1
        self.assertIn("NOT_INVOKED", render(self.source(case)))

    def test_fail_and_analytical_reduction(self):
        folder = self.bundle(redundant_failure())
        reduce_failure(folder)
        item = evidence(folder)
        self.assertEqual(item["result"]["verdict"], "FAIL")
        self.assertEqual(item["reduced"]["result"]["verdict"], "FAIL")
        self.assertEqual(len(item["reduced"]["removed"]), 2)
        self.assertIn("Reduced history is an analytical projection", render(folder))

    def test_tampered_and_unsupported_evidence_rejected(self):
        folder = self.bundle(history())
        (folder / "history.json").write_bytes((folder / "history.json").read_bytes() + b" ")
        with self.assertRaisesRegex(InvalidHistory, "hash mismatch"):
            evidence(folder)
        case = history()
        case["schemaVersion"] = 2
        with self.assertRaisesRegex(InvalidHistory, "unsupported history"):
            evidence(self.source(case))

    def test_reduction_tampering_and_script_injection(self):
        folder = self.bundle(redundant_failure())
        reduce_failure(folder)
        record = json.loads((folder / "reduction.json").read_text())
        record["reduced_sha256"] = "0" * 64
        (folder / "reduction.json").write_text(json.dumps(record))
        with self.assertRaisesRegex(InvalidHistory, "reduction does not match"):
            evidence(folder)
        case = history()
        case["observations"][0]["invokedAt"] = "<script>alert(1)</script>"
        with self.assertRaisesRegex(InvalidHistory, "timestamp"):
            evidence(self.source(case))
        self.assertNotIn("<script>alert(1)</script>", render(self.source(history())))


if __name__ == "__main__":
    unittest.main()
