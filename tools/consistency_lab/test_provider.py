import json
import subprocess
import unittest
from unittest.mock import patch

from oracle import EvidenceError, check
from recorder import capture


RUN = "00000000-0000-4000-8000-000000000016"
ROW = {"provider_request_id": RUN, "status": "AUTHORIZED", "provider_reference": "pay_test"}


def provider_capture():
    lines = [
        {"database": "payment_provider_lab_m16", "role": "lab_provider_reader",
         "captured_at": "2026-11-23 12:00:00+00", "transaction_snapshot": "10:10:",
         "transaction_isolation": "repeatable read", "transaction_read_only": "on", "role_can_write": False},
        {"name": "provider_payments", "columns": ["provider_request_id", "amount", "status",
         "provider_reference", "created_at"], "row_count": 1, "rows": [ROW]},
    ]
    with patch("recorder.subprocess.run", return_value=subprocess.CompletedProcess([], 0,
               "\n".join(json.dumps(line) for line in lines), "")) as run:
        bundle = capture(RUN, "localhost", 5432, "payment_provider_lab_m16", "lab_provider_reader",
                         "test-password", store="provider")
    assert "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY" in run.call_args.kwargs["input"]
    assert "test-password" not in json.dumps(bundle)
    return bundle


class ProviderEvidenceTest(unittest.TestCase):
    def test_observation_and_ambiguity(self):
        bundle = provider_capture()
        self.assertEqual([x["verdict"] for x in check(bundle)["results"]], ["PASS", "INCONCLUSIVE"])
        # CommerceCore's local UNKNOWN can coexist with a committed provider AUTHORIZED.
        local_payment = {"id": RUN, "status": "UNKNOWN"}
        self.assertEqual(local_payment["id"], bundle["sections"]["provider_payments"]["rows"][0]["provider_request_id"])
        self.assertEqual(check(bundle)["results"][1]["verdict"], "INCONCLUSIVE")

    def test_partial_and_invalid_evidence(self):
        bundle = provider_capture()
        bundle["sections"]["provider_payments"]["completeness"] = "unknown"
        self.assertEqual(check(bundle)["results"][0]["verdict"], "INCONCLUSIVE")
        for change in ({"status": "UNKNOWN"}, {"provider_request_id": "bad-id"},
                       {"provider_reference": None}):
            bundle = provider_capture()
            bundle["sections"]["provider_payments"]["rows"][0].update(change)
            with self.assertRaises(EvidenceError):
                check(bundle)

    def test_deterministic_and_target_guard(self):
        self.assertEqual(json.dumps(provider_capture(), sort_keys=True), json.dumps(provider_capture(), sort_keys=True))
        for database, role in (("payment_provider", "lab_provider_reader"),
                               ("payment_provider_lab_m16", "postgres")):
            with self.assertRaises(ValueError):
                capture(RUN, "localhost", 5432, database, role, "test-password", store="provider")


if __name__ == "__main__":
    unittest.main()
