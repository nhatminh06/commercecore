"""Offline checks of a versioned, explicitly scoped CommerceCore evidence bundle."""

import json
import sys


CHECKS = ("inventory_nonnegative", "checkout_identity", "order_reservation_state")


class EvidenceError(ValueError):
    pass


def _section(bundle, name, fields, identity):
    section = bundle.get("sections", {}).get(name)
    if section is None:
        return None
    if not isinstance(section, dict) or set(section) != {"source", "scope", "snapshot_id", "completeness", "rows"} or not isinstance(section.get("rows"), list):
        raise EvidenceError(f"{name}: expected rows array")
    if section.get("source") != f"commercecore.{name}" or section.get("scope") != "all_rows_at_one_db_snapshot" or not isinstance(section.get("snapshot_id"), str) or not section["snapshot_id"]:
        raise EvidenceError(f"{name}: invalid source or scope")
    if type(section.get("completeness")) is not str or section["completeness"] not in ("attested", "unknown"):
        raise EvidenceError(f"{name}: invalid completeness")
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
    if not isinstance(bundle, dict) or set(bundle) != {"schema_version", "run_id", "evidence_kind", "sections"}:
        raise EvidenceError("expected schema_version, run_id, evidence_kind, sections")
    if type(bundle["schema_version"]) is not int or bundle["schema_version"] != 1:
        raise EvidenceError("unsupported schema version")
    if not isinstance(bundle["run_id"], str) or not bundle["run_id"]:
        raise EvidenceError("invalid run_id")
    if bundle["evidence_kind"] != "synthetic_fixture":
        raise EvidenceError("v1 supports only synthetic fixtures; real capture requires a collector contract")
    if not isinstance(bundle["sections"], dict) or set(bundle["sections"]) - {
        "inventory", "orders", "inventory_reservations", "checkout_idempotency"
    }:
        raise EvidenceError("invalid sections")

    inventory = _section(bundle, "inventory", {"sku": str, "available_quantity": int}, ("sku",))
    orders = _section(bundle, "orders", {"id": str, "status": str}, ("id",))
    reservations = _section(bundle, "inventory_reservations", {
        "id": str, "sku": str, "quantity": int, "status": str, "order_id": (str, type(None))
    }, ("id",))
    mappings = _section(bundle, "checkout_idempotency", {
        "idempotency_key": str, "cart_id": str, "order_id": str
    }, ("idempotency_key",))

    if orders:
        for row in orders["rows"]:
            if row["status"] not in ("PENDING", "CONFIRMED", "CANCELLED"):
                raise EvidenceError("orders: unsupported status")
    if reservations:
        for row in reservations["rows"]:
            if row["quantity"] <= 0 or row["status"] not in ("ACTIVE", "CONFIRMED", "RELEASED", "EXPIRED"):
                raise EvidenceError("inventory_reservations: invalid quantity or status")

    one_snapshot = len({section["snapshot_id"] for section in (inventory, orders, reservations, mappings) if section}) <= 1

    def result(name, required, violations):
        if not one_snapshot or any(section is None or section["completeness"] != "attested" for section in required):
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
    return {"schema_version": 1, "run_id": bundle["run_id"], "results": [
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
