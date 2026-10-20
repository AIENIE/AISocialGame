import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from shared_target import resolve


class SharedTargetTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {"components": [{"canonical_component_id": "shared-mysql", "endpoints": {
            "develop": {"canonical_domain": "localmysql.testhut.top", "host_ref": "aienie-6",
                        "listeners": [{"name": "mysql", "protocol": "mysql", "port": 23306, "bind_scope": "lan-private"}]}
        }}]}
        self.values = {"ENV": "local", "APP_ENV": "local", "AIENIE_RUNTIME_PLANE": "windows-local",
                       "SPRING_DATASOURCE_URL": "jdbc:mysql://localmysql.testhut.top:23306/aisocialgame?serverTimezone=UTC&useSSL=false"}

    def test_develop_matrix_matches_explicit_local_product(self):
        self.assertEqual("PASS", resolve(self.catalog, self.values, "fixture")["status"])

    def test_alternate_host_port_database_and_environment_are_rejected(self):
        for old, new in [("localmysql", "mysql"), ("23306", "13306"), ("/aisocialgame", "/other")]:
            values = dict(self.values, SPRING_DATASOURCE_URL=self.values["SPRING_DATASOURCE_URL"].replace(old, new))
            self.assertEqual("UNKNOWN", resolve(self.catalog, values, "fixture")["status"])
        self.assertEqual("UNKNOWN", resolve(self.catalog, dict(self.values, ENV="production"), "fixture")["status"])


if __name__ == "__main__":
    unittest.main()
