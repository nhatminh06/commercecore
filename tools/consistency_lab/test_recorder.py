import copy
import hashlib
import json
import subprocess
import unittest
from unittest.mock import patch

from oracle import EvidenceError, check
from recorder import TABLES, capture, sql


RUN = "00000000-0000-4000-8000-000000000016"


def pg_output():
    meta = {"database": "commercecore_lab_m16", "role": "lab_reader",
            "captured_at": "2026-11-23 12:00:00+00", "transaction_snapshot": "10:10:",
            "transaction_isolation": "repeatable read", "transaction_read_only": "on", "role_can_write": False}
    rows = {
        "inventory": [{"sku": "SKU", "available_quantity": 2}],
        "orders": [{"id": "00000000-0000-4000-8000-000000000001", "status": "CONFIRMED"}],
        "inventory_reservations": [{"id": "00000000-0000-4000-8000-000000000002", "status": "CONFIRMED",
                                    "order_id": "00000000-0000-4000-8000-000000000001"}],
        "checkout_idempotency": [{"idempotency_key": "secret-key", "order_id": "00000000-0000-4000-8000-000000000001"}],
    }
    return [meta] + [{"name": name, "columns": list(columns), "row_count": len(rows[name]),
                      "rows": rows[name]} for name, columns in TABLES.items()]


def fake_capture(lines=None):
    output = pg_output() if lines is None else lines
    with patch("recorder.subprocess.run", return_value=subprocess.CompletedProcess([], 0,
               "\n".join(json.dumps(row) for row in output), "")) as run:
        bundle = capture(RUN, "127.0.0.1", 5432, "commercecore_lab_m16", "lab_reader", "test-password")
    self_args = run.call_args
    assert "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY" in self_args.kwargs["input"]
    assert "secret-key" not in json.dumps(bundle)
    return bundle


class RecorderTest(unittest.TestCase):
    def test_capture_and_oracle(self):
        bundle = fake_capture()
        self.assertEqual([r["verdict"] for r in check(bundle)["results"]], ["PASS"] * 3)
        self.assertEqual(bundle["sections"]["checkout_idempotency"]["rows"][0]["key_sha256"],
                         hashlib.sha256(b"secret-key").hexdigest())

    def test_deterministic_serialization(self):
        self.assertEqual(json.dumps(fake_capture(), sort_keys=True, separators=(",", ":")),
                         json.dumps(fake_capture(), sort_keys=True, separators=(",", ":")))

    def test_partial_and_missing_evidence(self):
        lines = pg_output()
        lines[1]["row_count"] = 2
        bundle = fake_capture(lines)
        self.assertEqual(check(bundle)["results"][0]["verdict"], "INCONCLUSIVE")
        bundle = fake_capture()
        del bundle["sections"]["orders"]
        self.assertEqual([r["verdict"] for r in check(bundle)["results"]],
                         ["PASS", "INCONCLUSIVE", "INCONCLUSIVE"])

    def test_malformed_capture(self):
        for mutate in (
            lambda x: x[1].update(columns=["wrong"]),
            lambda x: x[0].update(role_can_write=True),
            lambda x: x[0].update(transaction_isolation="read committed"),
        ):
            with self.subTest(mutate=mutate):
                lines = pg_output()
                mutate(lines)
                with self.assertRaises(ValueError):
                    fake_capture(lines)

    def test_oracle_rejects_false_coverage_and_invalid_state(self):
        bundle = fake_capture()
        bundle["sections"]["inventory"]["row_count"] = 2
        with self.assertRaises(EvidenceError):
            check(bundle)
        bundle = fake_capture()
        bundle["sections"]["inventory_reservations"]["rows"][0]["status"] = "UNKNOWN"
        with self.assertRaises(EvidenceError):
            check(bundle)
        bundle = fake_capture()
        bundle["sections"]["inventory_reservations"]["rows"][0]["status"] = "RELEASED"
        self.assertEqual(check(bundle)["results"][2]["verdict"], "FAIL")
        bundle = fake_capture()
        bundle["sections"]["orders"]["rows"][0]["id"] = "not-a-uuid"
        with self.assertRaises(EvidenceError):
            check(bundle)

    def test_invalid_target_and_connection_failure(self):
        for host, database, role in (("db.example.com", "commercecore_lab_m16", "lab_reader"),
                                     ("localhost", "commercecore", "lab_reader"),
                                     ("localhost", "commercecore_lab_m16", "postgres")):
            with self.assertRaises(ValueError):
                capture(RUN, host, 5432, database, role, "test-password")
        with patch("recorder.subprocess.run", return_value=subprocess.CompletedProcess([], 2, "", "connection refused")):
            with self.assertRaisesRegex(ValueError, "database capture failed"):
                capture(RUN, "localhost", 5432, "commercecore_lab_m16", "lab_reader", "test-password")

    def test_protocol_is_read_only(self):
        script = sql()
        self.assertIn("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY", script)
        self.assertIn("LIMIT 10001", script)
        self.assertNotIn("INSERT INTO", script)
        self.assertNotIn("UPDATE public", script)


if __name__ == "__main__":
    unittest.main()
