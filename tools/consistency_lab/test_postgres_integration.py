"""Opt-in isolated PostgreSQL qualification: LAB_POSTGRES_TEST=1."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

from oracle import check
from recorder import capture, sql


@unittest.skipUnless(os.environ.get("LAB_POSTGRES_TEST") == "1", "set LAB_POSTGRES_TEST=1 for Docker integration")
class PostgreSQLRecorderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "commercecore-lab-test-" + uuid.uuid4().hex[:10]
        cls.run_cmd(["docker", "run", "--rm", "-d", "--name", cls.container,
                 "-e", "POSTGRES_PASSWORD=isolated-test-password", "postgres:16"])
        try:
            for _ in range(60):
                ready = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-U", "postgres"],
                                       capture_output=True)
                if ready.returncode == 0:
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError("isolated PostgreSQL did not start")
            for _ in range(60):
                created = cls.run_cmd(["docker", "exec", cls.container, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1",
                                       "-c", "CREATE DATABASE commercecore_lab_m16"], check=False)
                if created.returncode == 0:
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError("isolated test database could not be created: " + created.stderr.strip())
            cls.run_cmd(["docker", "exec", cls.container, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1",
                         "-c", "CREATE DATABASE payment_provider_lab_m16"])
            for _ in range(60):
                logs = cls.run_cmd(["docker", "logs", cls.container])
                if (logs.stdout + logs.stderr).count("database system is ready to accept connections") >= 2:
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError("isolated PostgreSQL did not finish initialization")
            migrations = Path(__file__).resolve().parents[2] / "src/main/resources/db/migration"
            for migration in sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__")[0][1:])):
                cls.admin(migration.read_text())
            cls.admin("""
                INSERT INTO products (sku,name,price_amount) VALUES ('SKU','Test product',1.00);
                INSERT INTO carts VALUES ('00000000-0000-4000-8000-000000000003');
                INSERT INTO inventory VALUES ('SKU',2);
                INSERT INTO orders VALUES ('00000000-0000-4000-8000-000000000001','CONFIRMED',1.00,now());
                INSERT INTO inventory_reservations VALUES ('00000000-0000-4000-8000-000000000002','SKU',1,'CONFIRMED',now(),
                    '00000000-0000-4000-8000-000000000001');
                INSERT INTO checkout_idempotency VALUES ('private-test-key','00000000-0000-4000-8000-000000000003',
                    '00000000-0000-4000-8000-000000000001',now());
                CREATE ROLE lab_reader LOGIN PASSWORD 'reader-test-password';
                GRANT CONNECT ON DATABASE commercecore_lab_m16 TO lab_reader;
                GRANT USAGE ON SCHEMA public TO lab_reader;
                GRANT SELECT ON inventory,orders,inventory_reservations,checkout_idempotency TO lab_reader;
            """)
            provider_migration = Path(__file__).resolve().parents[2] / "payment-service/src/main/resources/db/migration/V1__create_provider_payments.sql"
            cls.admin(provider_migration.read_text(), database="payment_provider_lab_m16")
            cls.admin("""
                INSERT INTO provider_payments VALUES
                ('00000000-0000-4000-8000-000000000016',1.00,'AUTHORIZED','pay_test',now());
                CREATE ROLE lab_provider_reader LOGIN PASSWORD 'provider-reader-test-password';
                GRANT CONNECT ON DATABASE payment_provider_lab_m16 TO lab_provider_reader;
                GRANT USAGE ON SCHEMA public TO lab_provider_reader;
                GRANT SELECT ON provider_payments TO lab_provider_reader;
            """, database="payment_provider_lab_m16")
            cls.temp = tempfile.TemporaryDirectory(prefix="commercecore-lab-psql-")
            cls.wrapper = Path(cls.temp.name) / "psql"
            cls.wrapper.write_text("#!/bin/sh\nexec docker exec -i -e \"PGPASSWORD=$PGPASSWORD\" " + cls.container + " psql \"$@\"\n")
            cls.wrapper.chmod(0o700)
        except Exception:
            cls.run_cmd(["docker", "rm", "-f", cls.container], check=False)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()
        cls.run_cmd(["docker", "rm", "-f", cls.container], check=False)

    @staticmethod
    def run_cmd(command, *, input=None, check=True):
        result = subprocess.run(command, input=input, text=True, capture_output=True)
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result

    @classmethod
    def admin(cls, query, database="commercecore_lab_m16"):
        cls.run_cmd(["docker", "exec", "-i", cls.container, "psql", "-U", "postgres",
                 "-d", database, "-v", "ON_ERROR_STOP=1"], input=query)

    def get_bundle(self):
        return capture("00000000-0000-4000-8000-000000000016", "127.0.0.1", 5432,
                       "commercecore_lab_m16", "lab_reader", "reader-test-password", str(self.wrapper))

    def get_provider_bundle(self):
        return capture("00000000-0000-4000-8000-000000000016", "127.0.0.1", 5432,
                       "payment_provider_lab_m16", "lab_provider_reader", "provider-reader-test-password",
                       str(self.wrapper), store="provider")

    def test_provider_capture_and_read_only_role(self):
        bundle = self.get_provider_bundle()
        self.assertEqual([r["verdict"] for r in check(bundle)["results"]], ["PASS", "INCONCLUSIVE"])
        self.assertEqual(bundle["sections"]["provider_payments"]["row_count"], 1)
        self.assertEqual(set(bundle["sections"]["provider_payments"]["rows"][0]),
                         {"provider_request_id", "status", "provider_reference"})
        self.assertNotIn("provider-reader-test-password", json.dumps(bundle))
        attempted_write = self.run_cmd(["env", "PGPASSWORD=provider-reader-test-password", str(self.wrapper),
            "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-U", "lab_provider_reader",
            "-d", "payment_provider_lab_m16", "-c", "UPDATE provider_payments SET status='DECLINED'"], check=False)
        self.assertNotEqual(attempted_write.returncode, 0)
        self.assertIn("permission denied", attempted_write.stderr)

    def test_provider_schema_drift_and_permission_denial(self):
        self.admin("ALTER TABLE provider_payments ADD COLUMN unexpected integer;", "payment_provider_lab_m16")
        try:
            with self.assertRaisesRegex(ValueError, "unexpected schema"):
                self.get_provider_bundle()
        finally:
            self.admin("ALTER TABLE provider_payments DROP COLUMN unexpected;", "payment_provider_lab_m16")
        self.admin("REVOKE SELECT ON provider_payments FROM lab_provider_reader;", "payment_provider_lab_m16")
        try:
            with self.assertRaisesRegex(ValueError, "database capture failed"):
                self.get_provider_bundle()
        finally:
            self.admin("GRANT SELECT ON provider_payments TO lab_provider_reader;", "payment_provider_lab_m16")
        self.admin("GRANT INSERT ON provider_payments TO lab_provider_reader;", "payment_provider_lab_m16")
        try:
            with self.assertRaisesRegex(ValueError, "lacks required read-only provenance"):
                self.get_provider_bundle()
        finally:
            self.admin("REVOKE INSERT ON provider_payments FROM lab_provider_reader;", "payment_provider_lab_m16")

    def test_provider_partial_and_deterministic_evidence(self):
        first = self.get_provider_bundle()
        second = self.get_provider_bundle()
        self.assertEqual(json.dumps(first["sections"]["provider_payments"]["rows"], sort_keys=True),
                         json.dumps(second["sections"]["provider_payments"]["rows"], sort_keys=True))
        first["sections"]["provider_payments"]["row_count"] = 2
        first["sections"]["provider_payments"]["completeness"] = "unknown"
        self.assertEqual(check(first)["results"][0]["verdict"], "INCONCLUSIVE")

    def test_complete_capture_and_read_only_role(self):
        bundle = self.get_bundle()
        self.assertEqual([r["verdict"] for r in check(bundle)["results"]], ["PASS"] * 3)
        self.assertEqual([s["row_count"] for s in bundle["sections"].values()], [1] * 4)
        self.assertNotIn("private-test-key", json.dumps(bundle))
        attempted_write = self.run_cmd(["env", "PGPASSWORD=reader-test-password", str(self.wrapper), "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                    "-h", "127.0.0.1", "-p", "5432", "-U", "lab_reader",
                                    "-d", "commercecore_lab_m16", "-c", "UPDATE inventory SET available_quantity=0"],
                                   check=False)
        self.assertNotEqual(attempted_write.returncode, 0)
        self.assertIn("permission denied", attempted_write.stderr)

    def test_rejects_schema_change(self):
        self.admin("ALTER TABLE inventory ADD COLUMN unexpected integer;")
        try:
            with self.assertRaisesRegex(ValueError, "unexpected schema"):
                self.get_bundle()
        finally:
            self.admin("ALTER TABLE inventory DROP COLUMN unexpected;")

    def test_snapshot_survives_concurrent_commit(self):
        original = sql()
        delayed = original.replace("\nSELECT json_build_object('name','inventory'",
                                   "\nDO $$BEGIN PERFORM pg_sleep(1); END$$;\nSELECT json_build_object('name','inventory'")
        self.assertNotEqual(original, delayed)
        with patch("recorder.sql", return_value=delayed):
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=1) as pool:
                result = pool.submit(self.get_bundle)
                for _ in range(30):
                    active = self.run_cmd(["docker", "exec", self.container, "psql", "-U", "postgres",
                                           "-d", "commercecore_lab_m16", "-At", "-c",
                                           "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'DO %pg_sleep(1)%' AND state='active'"])
                    if active.stdout.strip() == "1":
                        break
                    time.sleep(0.05)
                else:
                    self.fail("reader did not reach the held snapshot")
                self.admin("UPDATE inventory SET available_quantity=1 WHERE sku='SKU';")
                bundle = result.result()
        self.assertEqual(bundle["sections"]["inventory"]["rows"][0]["available_quantity"], 2)
        self.admin("UPDATE inventory SET available_quantity=2 WHERE sku='SKU';")


if __name__ == "__main__":
    unittest.main()
