import copy
import json
import unittest

from oracle import EvidenceError, check


def section(name, rows):
    return {"source": f"commercecore.{name}", "scope": "all_rows_at_one_db_snapshot",
            "snapshot_id": "synthetic-1", "completeness": "attested", "rows": rows}


def valid():
    return {"schema_version": 1, "run_id": "synthetic-run", "evidence_kind": "synthetic_fixture", "sections": {
        "inventory": section("inventory", [{"sku": "SKU", "available_quantity": 2}]),
        "orders": section("orders", [{"id": "order-1", "status": "CONFIRMED"}]),
        "inventory_reservations": section("inventory_reservations", [
            {"id": "reservation-1", "sku": "SKU", "quantity": 1, "status": "CONFIRMED", "order_id": "order-1"}]),
        "checkout_idempotency": section("checkout_idempotency", [
            {"idempotency_key": "key-1", "cart_id": "cart-1", "order_id": "order-1"}]),
    }}


class OracleTest(unittest.TestCase):
    def test_valid_snapshot(self):
        self.assertEqual([r["verdict"] for r in check(valid())["results"]], ["PASS"] * 3)

    def test_targeted_mutations(self):
        for property_id, mutate in (
            ("inventory_nonnegative", lambda b: b["sections"]["inventory"]["rows"][0].update(available_quantity=-1)),
            ("checkout_identity", lambda b: b["sections"]["checkout_idempotency"]["rows"][0].update(order_id="absent")),
            ("order_reservation_state", lambda b: b["sections"]["inventory_reservations"]["rows"][0].update(status="RELEASED")),
        ):
            with self.subTest(property_id=property_id):
                bundle = valid()
                mutate(bundle)
                verdicts = {r["property"]: r["verdict"] for r in check(bundle)["results"]}
                self.assertEqual(verdicts[property_id], "FAIL")

    def test_missing_or_unattested_evidence(self):
        dependencies = {"inventory": {"inventory_nonnegative"},
                        "orders": {"checkout_identity", "order_reservation_state"},
                        "inventory_reservations": {"order_reservation_state"},
                        "checkout_idempotency": {"checkout_identity"}}
        for name in valid()["sections"]:
            for mode in ("missing", "unknown", "different snapshot"):
                with self.subTest(name=name, mode=mode):
                    bundle = valid()
                    if mode == "missing":
                        del bundle["sections"][name]
                    elif mode == "unknown":
                        bundle["sections"][name]["completeness"] = "unknown"
                    else:
                        bundle["sections"][name]["snapshot_id"] = "other"
                    verdicts = {r["property"]: r["verdict"] for r in check(bundle)["results"]}
                    expected = set(verdicts) if mode == "different snapshot" else dependencies[name]
                    self.assertTrue(all(verdicts[property_id] == "INCONCLUSIVE" for property_id in expected))

    def test_malformed_evidence(self):
        variants = []
        for change in (
            lambda b: b.update(schema_version=2),
            lambda b: b["sections"]["inventory"]["rows"].append(copy.deepcopy(b["sections"]["inventory"]["rows"][0])),
            lambda b: b["sections"]["inventory"]["rows"][0].update(available_quantity=True),
            lambda b: b["sections"]["orders"]["rows"][0].update(status="UNKNOWN"),
            lambda b: b["sections"]["inventory"]["rows"][0].update(extra=1),
            lambda b: b.update(evidence_kind="real_capture"),
            lambda b: b["sections"]["inventory"].update(completeness="complete"),
            lambda b: b["sections"]["inventory"].update(extra="ignored"),
            lambda b: b["sections"]["inventory_reservations"]["rows"][0].update(quantity=0),
            lambda b: b["sections"]["inventory_reservations"]["rows"][0].update(order_id=""),
        ):
            bundle = valid()
            change(bundle)
            variants.append(bundle)
        for bundle in variants:
            with self.subTest(bundle=json.dumps(bundle, sort_keys=True)):
                with self.assertRaises(EvidenceError):
                    check(bundle)

    def test_deterministic(self):
        self.assertEqual(json.dumps(check(valid()), sort_keys=True), json.dumps(check(valid()), sort_keys=True))

    def test_order_state_combinations(self):
        for order_status, reservation_status, expected in (
            ("CANCELLED", "ACTIVE", "FAIL"),
            ("CANCELLED", "CONFIRMED", "FAIL"),
            ("CANCELLED", "RELEASED", "PASS"),
            ("PENDING", "EXPIRED", "PASS"),
        ):
            with self.subTest(order_status=order_status, reservation_status=reservation_status):
                bundle = valid()
                bundle["sections"]["orders"]["rows"][0]["status"] = order_status
                bundle["sections"]["inventory_reservations"]["rows"][0]["status"] = reservation_status
                self.assertEqual(check(bundle)["results"][2]["verdict"], expected)

    def test_missing_reservation_owner(self):
        bundle = valid()
        bundle["sections"]["inventory_reservations"]["rows"][0]["order_id"] = "absent"
        self.assertEqual(check(bundle)["results"][2]["verdict"], "FAIL")

    def test_confirmed_order_without_reservation(self):
        bundle = valid()
        bundle["sections"]["inventory_reservations"]["rows"] = []
        self.assertEqual(check(bundle)["results"][2]["verdict"], "FAIL")


if __name__ == "__main__":
    unittest.main()
