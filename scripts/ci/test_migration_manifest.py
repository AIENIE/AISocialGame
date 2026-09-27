import copy
import json
import pathlib
import tempfile
import unittest

from production_migration_manifest import load_manifest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class MigrationManifestTests(unittest.TestCase):
    def test_rejects_ambiguous_or_unsafe_input(self):
        baseline = json.loads((ROOT / "backend/sql/migrations.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "migrations.json"
            mutations = [
                lambda v: v["entries"][1].update(ordinal=True),
                lambda v: v["entries"][1].update(ordinal=1),
                lambda v: v["entries"][1].update(file="../outside.sql"),
                lambda v: v["entries"][1].update(file="C:\\outside.sql"),
                lambda v: v["entries"][1].update(file="/outside.sql"),
                lambda v: v["entries"][1].update(file="schema.sql"),
                lambda v: v["entries"][1].update(kind="baseline"),
                lambda v: v["execution_plans"][0]["ordinals"].pop(),
                lambda v: v["execution_plans"][0].update(id="untrusted"),
                lambda v: v.update(baseline_includes=[True]),
            ]
            for mutation in mutations:
                with self.subTest(mutation=mutation):
                    value = copy.deepcopy(baseline)
                    mutation(value)
                    target.write_text(json.dumps(value))
                    with self.assertRaises(ValueError):
                        load_manifest(target)
            target.write_text('{"entries":[],"entries":[]}')
            with self.assertRaisesRegex(ValueError, "duplicate"):
                load_manifest(target)

    def test_all_incremental_sql_is_registered(self):
        manifest = load_manifest(ROOT / "backend/sql/migrations.json")
        registered = {entry["file"] for entry in manifest["entries"]}
        increments = {path.name for path in (ROOT / "backend/sql").glob("[0-9]*.sql")}
        self.assertLessEqual(increments, registered)


if __name__ == "__main__":
    unittest.main()
