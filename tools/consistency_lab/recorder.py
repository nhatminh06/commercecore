"""Read a bounded, coherent snapshot from a dedicated local PostgreSQL test database."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid


TABLES = {
    "inventory": ("sku", "available_quantity"),
    "orders": ("id", "status", "total_amount", "created_at"),
    "inventory_reservations": ("id", "sku", "quantity", "status", "expires_at", "order_id"),
    "checkout_idempotency": ("idempotency_key", "cart_id", "order_id", "created_at"),
}
PROVIDER_TABLES = {"provider_payments": ("provider_request_id", "amount", "status", "provider_reference", "created_at")}
LIMIT = 10000


def sql(store="commercecore"):
    tables = TABLES if store == "commercecore" else PROVIDER_TABLES
    permissions = " OR ".join(
        f"has_table_privilege(current_user,'public.{name}','INSERT,UPDATE,DELETE')" for name in tables)
    statements = ["BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;", "SET LOCAL statement_timeout = '10s';",
                  "SELECT json_build_object('database',current_database(),'role',current_user,"
                  "'captured_at',to_char(transaction_timestamp(),'YYYY-MM-DD\"T\"HH24:MI:SS.USOF'),"
                  "'transaction_snapshot',pg_current_snapshot()::text,"
                  "'transaction_isolation',current_setting('transaction_isolation'),"
                  "'transaction_read_only',current_setting('transaction_read_only'),"
                  "'role_can_write',(SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user)"
                  " OR has_database_privilege(current_user,current_database(),'CREATE')"
                  " OR EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%'"
                  " AND n.nspname <> 'information_schema' AND has_schema_privilege(current_user,n.oid,'CREATE'))"
                  " OR EXISTS (SELECT 1 FROM pg_tables t WHERE t.schemaname NOT IN ('pg_catalog','information_schema')"
                  " AND has_table_privilege(current_user,format('%I.%I',t.schemaname,t.tablename),'INSERT,UPDATE,DELETE,TRUNCATE'))"
                  " OR " + permissions + ");" ]
    for name in tables:
        order = "provider_request_id" if store == "provider" else "sku" if name == "inventory" else "idempotency_key" if name == "checkout_idempotency" else "id"
        columns = {"inventory": "sku, available_quantity", "orders": "id::text AS id, status",
                   "inventory_reservations": "id::text AS id, status, order_id::text AS order_id",
                   "checkout_idempotency": "idempotency_key, order_id::text AS order_id",
                   "provider_payments": "provider_request_id::text AS provider_request_id, status, provider_reference"}[name]
        statements.append(
            "SELECT json_build_object('name','" + name + "','row_count',(SELECT count(*) FROM public." + name + "),"
            "'columns',(SELECT coalesce(json_agg(column_name ORDER BY ordinal_position),'[]'::json)"
            " FROM information_schema.columns WHERE table_schema='public' AND table_name='" + name + "'),"
            "'rows',(SELECT coalesce(json_agg(row_to_json(t) ORDER BY t." + order + "),'[]'::json)"
            " FROM (SELECT " + columns + " FROM public." + name + " ORDER BY " + order + " LIMIT " + str(LIMIT + 1) + ") t));"
        )
    return "\n".join(statements + ["COMMIT;"])


def capture(run_id, host, port, database, role, password, psql="psql", store="commercecore"):
    if store not in ("commercecore", "provider"):
        raise ValueError("unsupported capture store")
    prefix = "commercecore_lab_" if store == "commercecore" else "payment_provider_lab_"
    role_pattern = r"lab_reader(?:_[a-zA-Z0-9_]+)?" if store == "commercecore" else r"lab_provider_reader(?:_[a-zA-Z0-9_]+)?"
    if host not in ("127.0.0.1", "::1", "localhost") or not re.fullmatch(prefix + r"[a-zA-Z0-9_]+", database):
        raise ValueError(f"only explicit loopback {prefix}* databases are allowed")
    if not re.fullmatch(role_pattern, role) or not 1 <= port <= 65535:
        raise ValueError("expected a dedicated lab reader role and valid local port")
    run_id = str(uuid.UUID(run_id))
    if not password:
        raise ValueError("LAB_DB_PASSWORD is required")
    env = {"PATH": os.environ.get("PATH", ""), "PGPASSWORD": password,
           "PGSSLMODE": "disable", "PGCONNECT_TIMEOUT": "5", "PGAPPNAME": "consistency-lab-recorder"}
    result = subprocess.run([psql, "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-h", host, "-p", str(port),
                             "-U", role, "-d", database], input=sql() if store == "commercecore" else sql(store), text=True, capture_output=True,
                            timeout=30, env=env, check=False)
    if result.returncode:
        raise ValueError("database capture failed (connection, schema, or permission error)")
    try:
        lines = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    except json.JSONDecodeError as exc:
        raise ValueError("invalid PostgreSQL capture output") from exc
    tables = TABLES if store == "commercecore" else PROVIDER_TABLES
    if len(lines) != len(tables) + 1 or any(lines[i + 1].get("name") != name for i, name in enumerate(tables)):
        raise ValueError("incomplete PostgreSQL capture output")
    meta = lines[0]
    if meta.get("database") != database or meta.get("role") != role or meta.get("role_can_write") is not False or meta.get("transaction_isolation") != "repeatable read" or meta.get("transaction_read_only") != "on":
        raise ValueError("capture target lacks required read-only provenance")
    snapshot = meta.get("transaction_snapshot")
    if not isinstance(snapshot, str) or not snapshot:
        raise ValueError("missing PostgreSQL snapshot identity")
    sections = {}
    for item in lines[1:]:
        name = item["name"]
        if item.get("columns") != list(tables[name]) or type(item.get("row_count")) is not int or item["row_count"] < 0 or not isinstance(item.get("rows"), list):
            raise ValueError(f"{name}: unexpected schema or count")
        rows = item["rows"]
        if name == "checkout_idempotency":
            rows = [{"key_sha256": hashlib.sha256(row["idempotency_key"].encode()).hexdigest(),
                     "order_id": row["order_id"]} for row in rows]
        sections[name] = {"source": f"{store}.{name}", "scope": "all_rows_at_one_db_snapshot",
                          "snapshot_id": snapshot, "columns": item["columns"], "row_count": item["row_count"],
                          "completeness": "verified" if len(rows) == item["row_count"] and len(rows) <= LIMIT else "unknown",
                          "rows": rows}
    bundle = {"schema_version": 2, "run_id": run_id, "evidence_kind": "postgres_snapshot" if store == "commercecore" else "provider_postgres_snapshot",
              "capture": {"source_id": f"{store}-local-test", "host": host, "port": port, "database": database,
                          "role": role, "captured_at": re.sub(r"([+-]\d{2})$", r"\1:00", meta["captured_at"]), "transaction_snapshot": snapshot,
                          "transaction_isolation": "repeatable read", "transaction_read_only": True},
              "sections": sections}
    return bundle


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--database", required=True)
    parser.add_argument("--role", required=True)
    parser.add_argument("--store", choices=("commercecore", "provider"), default="commercecore")
    args = parser.parse_args()
    try:
        bundle = capture(args.run_id, args.host, args.port, args.database, args.role,
                         os.environ.get("LAB_DB_PASSWORD"), store=args.store)
        print(json.dumps(bundle, sort_keys=True, separators=(",", ":")))
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError) as exc:
        print(json.dumps({"error": str(exc)}, sort_keys=True), file=sys.stderr)
        sys.exit(2)
