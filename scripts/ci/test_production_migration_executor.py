#!/usr/bin/env python3
from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import pathlib
import shutil
import stat
import sys
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[2]
EXECUTOR = ROOT / "scripts" / "ci" / "production-migration-executor"


def load_executor():
    loader = importlib.machinery.SourceFileLoader("social_production_migration_executor", str(EXECUTOR))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    if spec is None:
        raise RuntimeError("migration executor import specification is unavailable")
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


executor = load_executor()


class ProductionMigrationExecutorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        for directory in ("backend", "release", ".aienie-platform"):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        files = {
            "backend/app.jar": b"signed-social-backend\n",
            "backend/production-migration-entrypoint.sh": b"#!/bin/sh\nexit 0\n",
        }
        for relative, raw in files.items():
            (self.root / relative).write_bytes(raw)
        shutil.copyfile(EXECUTOR, self.root / "release/production-migration-executor")
        shutil.copyfile(ROOT / "scripts/ci/production_migration_manifest.py",
                        self.root / "release/production_migration_manifest.py")
        subprocess.run([sys.executable, str(ROOT / "scripts/ci/write-production-sql-ledger.py"),
                        str(ROOT), str(self.root / "release/migrations")], check=True)
        for path in self.root.rglob("*"):
            if path.is_file():
                os.chmod(path, 0o555 if path.name.endswith(".sh") or path.name == EXECUTOR.name else 0o444)
        subprocess.run([sys.executable, str(ROOT / "scripts/ci/write-production-migration-artifacts.py"),
                        str(self.root), str(self.root / "release/production-migration-artifacts.json")], check=True)
        self.plan_raw = (self.root / "release/migrations/production-plan.json").read_bytes()
        os.chmod(self.root / "release/production-migration-artifacts.json", 0o444)

        self.artifact_sha = "sha256:" + "a" * 64
        self.image_bindings = [
            {
                "environment_variable": "AISOCIALGAME_BACKEND_IMAGE",
                "reference": "ghcr.io/aienie/ai-social-game@sha256:" + "1" * 64,
                "role": "backend",
            },
            {
                "environment_variable": "AISOCIALGAME_FRONTEND_IMAGE",
                "reference": "ghcr.io/aienie/ai-social-game@sha256:" + "2" * 64,
                "role": "frontend",
            },
        ]
        image_closure = sorted(item["reference"] for item in self.image_bindings)
        outer = {
            "canonical_component_id": "ai-social-game",
            "component": "ai-social-game",
            "environment": "production",
            "image_bindings": self.image_bindings,
            "image_bindings_digest": executor.digest_bytes(executor.canonical(self.image_bindings)),
            "image_closure": image_closure,
            "image_closure_digest": executor.digest_bytes(executor.canonical(image_closure)),
            "operation": "release",
            "project_key": "ai-social-game",
            "release_id": "release-fixture",
            "release_version": "20260823001",
            "schema_version": "v4",
        }
        outer_raw = self.write_canonical(".aienie-platform/production-release-manifest-v4.json", outer, 0o444)
        publication = {key: "sha256:" + "b" * 64 for key in executor.PUBLICATION_KEYS}
        publication.update({
            "artifact_sha256": self.artifact_sha,
            "canonical_component_id": "ai-social-game",
            "outer_manifest_sha256": executor.digest_bytes(outer_raw),
            "runtime_image_bindings_sha256": outer["image_bindings_digest"],
            "release_id": "release-fixture",
            "release_version": "20260823001",
            "schema_version": "aienie-production-publication-receipt-v1",
        })
        publication_raw = self.write_canonical(
            ".aienie-platform/production-publication-receipt.json", publication, 0o444
        )
        signature = self.root / ".aienie-platform/production-publication-receipt.json.sig"
        signature.write_bytes(b"s" * 64)
        os.chmod(signature, 0o444)

        inventory = []
        for path in sorted(
            (item for item in self.root.rglob("*") if item.is_file() and ".aienie-platform" not in item.parts),
            key=lambda item: item.relative_to(self.root).as_posix(),
        ):
            inventory.append({
                "path": path.relative_to(self.root).as_posix(),
                "sha256": executor.digest_file(path),
                "size": path.stat().st_size,
            })
        platform_files = []
        for relative in executor.PLATFORM_FILES:
            path = self.root / relative
            platform_files.append({
                "path": relative,
                "sha256": executor.digest_file(path),
                "size": path.stat().st_size,
            })
        closure = {key: "sha256:" + "c" * 64 for key in executor.CLOSURE_KEYS}
        closure.update({
            "artifact_file_count": len(inventory),
            "artifact_files": inventory,
            "artifact_files_sha256": executor.digest_bytes(executor.canonical(inventory)),
            "artifact_total_bytes": sum(item["size"] for item in inventory),
            "artifact_sha256": self.artifact_sha,
            "canonical_component_id": "ai-social-game",
            "capture_semantics": "exact-listed-files-plus-signed-closure-pair-only",
            "closure_pair": [
                ".aienie-platform/production-backup-closure.json",
                ".aienie-platform/production-backup-closure.json.sig",
            ],
            "outer_manifest_sha256": executor.digest_bytes(outer_raw),
            "platform_files": platform_files,
            "platform_files_sha256": executor.digest_bytes(executor.canonical(platform_files)),
            "protected_material_policy": "env-admin-cap-private-key-and-protected-overlays-excluded",
            "publication_receipt_sha256": executor.digest_bytes(publication_raw),
            "release_id": "release-fixture",
            "release_version": "20260823001",
            "schema_version": "aienie-production-backup-closure-v1",
        })
        closure_raw = self.write_canonical(".aienie-platform/production-backup-closure.json", closure, 0o444)
        self.environment = {
            "AIENIE_MIGRATION_ARTIFACT_SHA256": self.artifact_sha,
            "AIENIE_MIGRATION_BACKUP_CLOSURE_SHA256": executor.digest_bytes(closure_raw),
            "AIENIE_MIGRATION_MANIFEST_SHA256": executor.digest_bytes(outer_raw),
            "AIENIE_MIGRATION_PUBLICATION_RECEIPT_SHA256": executor.digest_bytes(publication_raw),
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_canonical(self, relative: str, value: object, mode: int) -> bytes:
        raw = executor.canonical(value)
        path = self.root / relative
        path.write_bytes(raw)
        os.chmod(path, mode)
        return raw

    def test_signed_platform_plan_and_artifact_closure_pass(self) -> None:
        with mock.patch.dict(os.environ, self.environment, clear=False):
            observed = executor.validate_platform(self.root)
        manifest_sha, entries = executor.validate_migration_artifacts(self.root)
        with mock.patch.dict(
            os.environ,
            {"AIENIE_MIGRATION_PLAN": str(self.root / executor.PLAN)},
            clear=False,
        ):
            executor.validate_plan_authority(self.root)

        self.assertEqual(self.artifact_sha, observed["AIENIE_MIGRATION_ARTIFACT_SHA256"])
        self.assertEqual(
            self.image_bindings[0]["reference"], observed["AISOCIALGAME_BACKEND_IMAGE"]
        )
        self.assertTrue(manifest_sha.startswith("sha256:"))
        self.assertEqual(len(json.loads((ROOT / "backend/sql/migrations.json").read_text())["entries"]), entries)

    def test_platform_sql_or_plan_authority_tamper_fails_closed(self) -> None:
        outer = self.root / ".aienie-platform/production-release-manifest-v4.json"
        original_outer = outer.read_bytes()
        os.chmod(outer, 0o644)
        outer.write_bytes(original_outer + b"\n")
        os.chmod(outer, 0o444)
        with mock.patch.dict(os.environ, self.environment, clear=False):
            with self.assertRaisesRegex(executor.ContractError, "not canonical|digest drifted"):
                executor.validate_platform(self.root)
        os.chmod(outer, 0o644)
        outer.write_bytes(original_outer)
        os.chmod(outer, 0o444)

        sql = self.root / "release/migrations/sql/schema.sql"
        os.chmod(sql, 0o644)
        sql.write_bytes(sql.read_bytes() + b"-- tampered\n")
        os.chmod(sql, 0o444)
        with self.assertRaisesRegex(executor.ContractError, "artifact entry drifted"):
            executor.validate_migration_artifacts(self.root)

        with mock.patch.dict(os.environ, {"AIENIE_MIGRATION_PLAN": "/tmp/untrusted.json"}, clear=False):
            with self.assertRaisesRegex(executor.ContractError, "plan authority drifted"):
                executor.validate_plan_authority(self.root)

    def test_database_receipt_requires_exact_truthful_common_schema(self) -> None:
        receipt = {
            "action": "execute",
            "applied_entry_count": 1,
            "canonical_component_id": "ai-social-game",
            "current_id": "fresh-empty-schema",
            "migration_checksum_sha256": executor.digest_bytes(self.plan_raw),
            "mutation_performed": True,
            "pending_entry_count_after": 0,
            "pending_entry_count_before": 1,
            "schema_version": "aienie-production-migration-database-result-v1",
            "status": "current",
        }
        completed = subprocess.CompletedProcess([], 0, stdout=executor.canonical(receipt), stderr=b"")
        uid = getattr(os, "getuid", lambda: 1000)()
        gid = getattr(os, "getgid", lambda: 1000)()
        platform = {
            item["environment_variable"]: item["reference"] for item in self.image_bindings
        }
        with mock.patch.object(executor.subprocess, "run", return_value=completed) as run:
            self.assertEqual(receipt, executor.run_container(self.root, uid, gid, "execute", platform))
        submitted = run.call_args.kwargs["env"]
        self.assertEqual(
            {
                "AIENIE_RUNTIME_GID",
                "AIENIE_RUNTIME_UID",
                "AISOCIALGAME_BACKEND_IMAGE",
                "AISOCIALGAME_FRONTEND_IMAGE",
                "LC_ALL",
                "PATH",
            },
            set(submitted),
        )
        self.assertEqual(self.image_bindings[0]["reference"], submitted["AISOCIALGAME_BACKEND_IMAGE"])

        completed.stdout = executor.canonical({**receipt, "mutation_performed": False})
        with mock.patch.object(executor.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(executor.ContractError, "execute result drifted"):
                executor.run_container(self.root, uid, gid, "execute", platform)

        completed.stdout = executor.canonical({**receipt, "credential": "forbidden"})
        with mock.patch.object(executor.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(executor.ContractError, "contract drifted"):
                executor.run_container(self.root, uid, gid, "execute", platform)

        with self.assertRaisesRegex(executor.ContractError, "image environment is incomplete"):
            executor.run_container(self.root, uid, gid, "execute", {})

    def test_checkpoint_receipt_allows_none_identity_only_for_checkpoint(self) -> None:
        receipt = {
            "action": "checkpoint",
            "applied_entry_count": 0,
            "canonical_component_id": "ai-social-game",
            "current_id": "none",
            "migration_checksum_sha256": "none",
            "mutation_performed": False,
            "pending_entry_count_after": 0,
            "pending_entry_count_before": 0,
            "schema_version": "aienie-production-migration-database-result-v1",
            "status": "checkpoint-created",
        }
        completed = subprocess.CompletedProcess([], 0, stdout=executor.canonical(receipt), stderr=b"")
        uid = getattr(os, "getuid", lambda: 1000)()
        gid = getattr(os, "getgid", lambda: 1000)()
        platform = {
            item["environment_variable"]: item["reference"] for item in self.image_bindings
        }
        with mock.patch.object(executor.subprocess, "run", return_value=completed):
            self.assertEqual(receipt, executor.run_container(self.root, uid, gid, "checkpoint", platform))


if __name__ == "__main__":
    unittest.main()
