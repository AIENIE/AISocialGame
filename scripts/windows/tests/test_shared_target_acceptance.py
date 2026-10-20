import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location("shared_target", Path(__file__).parents[1] / "support/shared_target.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class AcceptanceTargetTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {"components": [{"canonical_component_id": "shared-mysql", "endpoints": {"develop": {"canonical_domain": "localmysql.testhut.top", "host_ref": "aienie-6", "listeners": [{"name": "mysql", "protocol": "mysql", "port": 23306, "bind_scope": "lan-private"}]}}}]}
        self.values = {"ENV": "local", "APP_ENV": "local", "SPRING_PROFILES_ACTIVE": "local", "AIENIE_RUNTIME_PLANE": "windows-local", "SPRING_DATASOURCE_URL": "jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social?useSSL=false"}
    def test_isolated_schema_needs_explicit_acceptance_flag(self):
        self.assertEqual("UNKNOWN", module.resolve(self.catalog, self.values, "hash")["status"])
        result = module.resolve(self.catalog, self.values, "hash", "aienie_emergency_20261004_social")
        self.assertEqual("PASS", result["status"])
        self.assertEqual("aienie_emergency_20261004_social", result["database"])
    def test_acceptance_keeps_matrix_host_port_and_exact_schema(self):
        for url in ["jdbc:mysql://other.testhut.top:23306/aienie_emergency_20261004_social", "jdbc:mysql://localmysql.testhut.top:3306/aienie_emergency_20261004_social", "jdbc:mysql://localmysql.testhut.top:23306/aisocialgame"]:
            self.values["SPRING_DATASOURCE_URL"] = url
            self.assertEqual("UNKNOWN", module.resolve(self.catalog,self.values,"hash","aienie_emergency_20261004_social")["status"])
        with self.assertRaises(ValueError): module.resolve(self.catalog,self.values,"hash","another_schema")
if __name__ == "__main__": unittest.main()
