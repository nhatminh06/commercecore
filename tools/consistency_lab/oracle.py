"""Offline checks of a versioned, explicitly scoped CommerceCore evidence bundle."""

import json
import re
import sys
import uuid
from datetime import datetime


CHECKS = ("inventory_nonnegative", "checkout_identity", "order_reservation_state")
V2_COLUMNS = {
    "inventory": ["sku", "available_quantity"],
    "orders": ["id", "status", "total_amount", "created_at"],
    "inventory_reservations": ["id", "sku", "quantity", "status", "expires_at", "order_id"],
    "checkout_idempotency": ["idempotency_key", "cart_id", "order_id", "created_at"],
}


class EvidenceError(ValueError):
    pass


def _section(bundle, name, fields, identity):
    section = bundle.get("sections", {}).get(name)
    if section is None:
        return None
    expected = {"source", "scope", "snapshot_id", "completeness", "rows"}
    if bundle["schema_version"] == 2:
        expected |= {"columns", "row_count"}
    if not isinstance(section, dict) or set(section) != expected or not isinstance(section.get("rows"), list):
        raise EvidenceError(f"{name}: expected rows array")
    if section.get("source") != f"commercecore.{name}" or section.get("scope") != "all_rows_at_one_db_snapshot" or not isinstance(section.get("snapshot_id"), str) or not section["snapshot_id"]:
        raise EvidenceError(f"{name}: invalid source or scope")
    allowed = ("attested", "unknown") if bundle["schema_version"] == 1 else ("verified", "unknown")
    if type(section.get("completeness")) is not str or section["completeness"] not in allowed:
        raise EvidenceError(f"{name}: invalid completeness")
    if bundle["schema_version"] == 2:
        count = section["row_count"]
        if section["columns"] != V2_COLUMNS[name] or type(count) is not int or count < len(section["rows"]) or count < 0:
            raise EvidenceError(f"{name}: invalid schema or row count")
        if section["completeness"] == "verified" and count != len(section["rows"]):
            raise EvidenceError(f"{name}: false completeness claim")
    seen = set()
    for row in section["rows"]:
        if not isinstance(row, dict) or set(row) != set(fields):
            raise EvidenceError(f"{name}: invalid row fields")
        if any(type(row[key]) not in (kind if isinstance(kind, tuple) else (kind,)) or (isinstance(row[key], str) and not row[key]) for key, kind in fields.items()):
            raise EvidenceError(f"{name}: invalid row value")
        key = tuple(row[field] for field in identity)
        if key in seen:
            raise EvidenceError(f"{name}: duplicate identity {key}")
        seen.add(key)
    return section


def check(bundle):
    if not isinstance(bundle, dict) or set(bundle) not in ({"schema_version", "run_id", "evidence_kind", "sections"},
                                                           {"schema_version", "run_id", "evidence_kind", "sections", "capture"}):
        raise EvidenceError("expected schema_version, run_id, evidence_kind, sections")
    version = bundle["schema_version"]
    if type(version) is not int or version not in (1, 2):
        raise EvidenceError("unsupported schema version")
    if not isinstance(bundle["run_id"], str) or not bundle["run_id"]:
        raise EvidenceError("invalid run_id")
    if version == 1:
        if "capture" in bundle or bundle["evidence_kind"] != "synthetic_fixture":
            raise EvidenceError("v1 supports only synthetic fixtures")
    else:
        capture = bundle.get("capture")
        if bundle["evidence_kind"] != "postgres_snapshot" or not isinstance(capture, dict) or set(capture) != {
            "source_id", "host", "port", "database", "role", "captured_at", "transaction_snapshot",
            "transaction_isolation", "transaction_read_only"
        }:
            raise EvidenceError("invalid v2 capture provenance")
        if capture["source_id"] != "commercecore-local-test" or capture["host"] not in ("127.0.0.1", "::1", "localhost") or not re.fullmatch(r"commercecore_lab_[a-zA-Z0-9_]+", str(capture["database"])) or not re.fullmatch(r"lab_reader(?:_[a-zA-Z0-9_]+)?", str(capture["role"])) or type(capture["port"]) is not int or not 1 <= capture["port"] <= 65535 or capture["transaction_isolation"] != "repeatable read" or capture["transaction_read_only"] is not True or not isinstance(capture["transaction_snapshot"], str) or not capture["transaction_snapshot"]:
            raise EvidenceError("unqualified v2 capture provenance")
        try:
            uuid.UUID(bundle["run_id"])
            captured_at = datetime.fromisoformat(capture["captured_at"])
        except (ValueError, TypeError) as exc:
            raise EvidenceError("invalid v2 run identity or capture time") from exc
        if captured_at.tzinfo is None:
            raise EvidenceError("v2 capture time requires an offset")
    if not isinstance(bundle["sections"], dict) or set(bundle["sections"]) - {
        "inventory", "orders", "inventory_reservations", "checkout_idempotency"
    }:
        raise EvidenceError("invalid sections")

    inventory = _section(bundle, "inventory", {"sku": str, "available_quantity": int}, ("sku",))
    orders = _section(bundle, "orders", {"id": str, "status": str}, ("id",))
    reservation_fields = {"id": str, "status": str, "order_id": (str, type(None))}
    mapping_fields = {"key_sha256": str, "order_id": str}
    if version == 1:
        reservation_fields.update({"sku": str, "quantity": int})
        mapping_fields = {"idempotency_key": str, "cart_id": str, "order_id": str}
    reservations = _section(bundle, "inventory_reservations", reservation_fields, ("id",))
    mappings = _section(bundle, "checkout_idempotency", mapping_fields,
                        ("idempotency_key",) if version == 1 else ("key_sha256",))
    if version == 2:
        for section in (inventory, orders, reservations, mappings):
            if section is not None and section["snapshot_id"] != bundle["capture"]["transaction_snapshot"]:
                raise EvidenceError("section snapshot does not match capture")
        def valid_uuid(value):
            try:
                return str(uuid.UUID(value)) == value
            except (ValueError, TypeError):
                return False
        if inventory and any(len(row["sku"]) > 64 for row in inventory["rows"]):
            raise EvidenceError("inventory: invalid sku")
        if orders and any(not valid_uuid(row["id"]) for row in orders["rows"]):
            raise EvidenceError("orders: invalid id")
        if reservations and any(not valid_uuid(row["id"]) or (row["order_id"] is not None and not valid_uuid(row["order_id"])) for row in reservations["rows"]):
            raise EvidenceError("inventory_reservations: invalid id")
        if mappings and any(not re.fullmatch(r"[0-9a-f]{64}", row["key_sha256"]) or not valid_uuid(row["order_id"]) for row in mappings["rows"]):
            raise EvidenceError("checkout_idempotency: invalid identity")

    if orders:
        for row in orders["rows"]:
            if row["status"] not in ("PENDING", "CONFIRMED", "CANCELLED"):
                raise EvidenceError("orders: unsupported status")
    if reservations:
        for row in reservations["rows"]:
            if (version == 1 and row["quantity"] <= 0) or row["status"] not in ("ACTIVE", "CONFIRMED", "RELEASED", "EXPIRED"):
                raise EvidenceError("inventory_reservations: invalid quantity or status")

    one_snapshot = len({section["snapshot_id"] for section in (inventory, orders, reservations, mappings) if section}) <= 1

    def result(name, required, violations):
        complete = "attested" if version == 1 else "verified"
        if not one_snapshot or any(section is None or section["completeness"] != complete for section in required):
            return {"property": name, "verdict": "INCONCLUSIVE", "reason": "missing or incomplete evidence"}
        if violations:
            return {"property": name, "verdict": "FAIL", "reason": sorted(violations)[0]}
        return {"property": name, "verdict": "PASS", "reason": "holds within attested snapshot scope"}

    stock_bad = [] if inventory is None else [f"negative stock: {r['sku']}" for r in inventory["rows"] if r["available_quantity"] < 0]
    order_ids = set() if orders is None else {r["id"] for r in orders["rows"]}
    mapping_bad = [] if mappings is None or orders is None else [
        f"missing order: {r['order_id']}" for r in mappings["rows"] if r["order_id"] not in order_ids
    ]
    reservation_bad = []
    if orders is not None and reservations is not None:
        order_status = {r["id"]: r["status"] for r in orders["rows"]}
        owned = {r["order_id"] for r in reservations["rows"] if r["order_id"] is not None}
        reservation_bad.extend(f"confirmed order has no reservation: {r['id']}" for r in orders["rows"]
                               if r["status"] == "CONFIRMED" and r["id"] not in owned)
        for row in reservations["rows"]:
            owner = row["order_id"]
            if owner is not None and owner not in order_status:
                reservation_bad.append(f"missing owner: {owner}")
            elif owner is not None and order_status[owner] == "CONFIRMED" and row["status"] != "CONFIRMED":
                reservation_bad.append(f"confirmed order lacks confirmed reservation: {owner}")
            elif owner is not None and order_status[owner] == "CANCELLED" and row["status"] in ("ACTIVE", "CONFIRMED"):
                reservation_bad.append(f"cancelled order retains stock: {owner}")
    return {"schema_version": version, "run_id": bundle["run_id"], "results": [
        result(CHECKS[0], (inventory,), stock_bad),
        result(CHECKS[1], (orders, mappings), mapping_bad),
        result(CHECKS[2], (orders, reservations), reservation_bad),
    ]}


if __name__ == "__main__":
    try:
        with open(sys.argv[1], encoding="utf-8") as evidence:
            output = check(json.load(evidence))
        print(json.dumps(output, sort_keys=True))
    except (IndexError, OSError, ValueError) as exc:
        print(json.dumps({"error": str(exc)}, sort_keys=True))
        sys.exit(2)
