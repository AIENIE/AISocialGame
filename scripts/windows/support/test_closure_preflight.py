import argparse
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
from closure_preflight import budget, check, database_read, ledger, main, migration_diff, network, proposal, version
from shared_target import resolve
from closure_identity import prepare, artifact
import zipfile

ROOT=Path(__file__).resolve().parents[3]


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.catalog={"components":[dict(canonical_component_id="shared-mysql",runtime=dict(host="aienie-6",plane="staging-shared-data",listeners=[dict(protocol="mysql",port=13306)]),endpoints=dict(develop=dict(canonical_domain="localbase.testhut.top",host_ref="local-services-aienie-devvm",listeners=[dict(name="mysql",protocol="mysql",port=23306,bind_scope="lan-private")])),state=dict(current="verified-running",verified_at="2026-09-22"),data=dict(authority="authoritative"),security=dict(tls="disabled-in-verified-staging"))]}
        self.values=dict(ENV="local",AIENIE_RUNTIME_PLANE="windows-local",SPRING_DATASOURCE_URL="jdbc:mysql://localbase.testhut.top:23306/aisocialgame?useSSL=false")

    def test_matching_target_and_conflicts_never_use_a_fallback(self):
        self.assertEqual("PASS",resolve(self.catalog,self.values,"a"*64)["status"])
        for url in ("jdbc:mysql://localbase.testhut.top:13306/aisocialgame", "jdbc:mysql://other:23306/aisocialgame", "jdbc:mysql://localbase.testhut.top:23306/other", "jdbc:mysql://localbase.testhut.top:23306/aisocialgame?sessionVariables=evil"):
            values={**self.values,"SPRING_DATASOURCE_URL":url}
            target=resolve(self.catalog,values,"a"*64)
            self.assertEqual("UNKNOWN",target["status"])
            with patch("closure_preflight.subprocess.run") as run:
                self.assertEqual("NOT_RUN",database_read(ROOT,Path("private"),target)["status"])
                run.assert_not_called()
        self.assertEqual("UNKNOWN",resolve(self.catalog,{**self.values,"ENV":"production"},"a"*64)["status"])
        no_local_ref=copy.deepcopy(self.catalog)
        del no_local_ref["components"][0]["endpoints"]["develop"]["host_ref"]
        self.assertEqual("UNKNOWN",resolve(no_local_ref,self.values,"a"*64)["status"])
        wrong_local_listener=copy.deepcopy(self.catalog)
        wrong_local_listener["components"][0]["endpoints"]["develop"]["listeners"][0]["port"]=13306
        self.assertEqual("UNKNOWN",resolve(wrong_local_listener,self.values,"a"*64)["status"])
        staging_changed=copy.deepcopy(self.catalog)
        staging_changed["components"][0]["runtime"]["listeners"][0]["port"]=3306
        staging_changed["components"][0]["security"]["tls"]="required"
        self.assertEqual("PASS",resolve(staging_changed,self.values,"a"*64)["status"])

    def test_network_uses_local_tls_listeners_and_exported_root(self):
        catalog={"components":[]}
        for name,port in (("ai-service",22011),("user-service",22001)):
            catalog["components"].append(dict(canonical_component_id=name,
                runtime=dict(listeners=[dict(protocol="grpc",ingress_port=12011)]),
                endpoints=dict(develop=dict(canonical_domain="local"+name.replace("-", "")+".testhut.top",
                    listeners=[dict(protocol="https",port=443),dict(protocol="grpc-tls",port=port)]))))
        tcp=Mock();tcp.__enter__=Mock(return_value=tcp);tcp.__exit__=Mock(return_value=False)
        tls=Mock();tls.__enter__=Mock(return_value=tls);tls.__exit__=Mock(return_value=False)
        tls.version.return_value="TLSv1.3";tls.getpeercert.return_value={"notAfter":"fixture"}
        context=Mock();context.wrap_socket.return_value=tls
        with patch("closure_preflight.socket.getaddrinfo",return_value=[(None,None,None,None,("127.0.0.1",0))]), \
                patch("closure_preflight.socket.create_connection",return_value=tcp) as connect, \
                patch("closure_preflight.ssl.create_default_context",return_value=context) as ssl_context:
            self.assertEqual("PASS",network(catalog)["status"])
        ports={call.args[0][1] for call in connect.call_args_list}
        self.assertEqual({443,22001,22011},ports)
        self.assertNotIn(12011,ports)
        self.assertTrue(all(call.kwargs["cafile"].endswith("localcert-root-ca.crt")
                            for call in ssl_context.call_args_list))

    def test_budget_missing_unknown_disabled_or_wrong_run_is_not_zero(self):
        values=dict(APP_AI_VALIDATION_RUN_ID="game-realism-v2-live-20260912",APP_AI_VALIDATION_CALL_LIMIT="210")
        data=dict(status="PASS",budgetTablePresent=True,budget=[dict(id=values["APP_AI_VALIDATION_RUN_ID"],consumed=105)])
        self.assertEqual(105,budget(values,data)["evidence"]["remaining"])
        self.assertIsNone(budget(values,dict(status="NOT_RUN"))["evidence"]["rowPresent"])
        for limit in ("","0","True","-1"):
            self.assertEqual("UNKNOWN",budget({**values,"APP_AI_VALIDATION_CALL_LIMIT":limit},data)["status"])
        for rows in ([],[dict(id="different",consumed=0)],[dict(id=values["APP_AI_VALIDATION_RUN_ID"],consumed=True)]):
            result=budget(values,{**data,"budget":rows})
            self.assertEqual("UNKNOWN",result["status"]);self.assertIsNone(result["evidence"]["consumed"])

    def test_ledger_is_read_only_and_bad_sequence_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"ledger"
            p.write_text('{"event":"ATTEMPT_RESERVED","comparisonAttempt":1}\n')
            before=p.read_bytes();result=ledger(p)
            self.assertEqual(361,result["requestedFullCumulative"]);self.assertEqual(before,p.read_bytes())
            for text in ('', '{"event":"ATTEMPT_RESERVED","comparisonAttempt":true}\n', '{"event":"ATTEMPT_RESERVED","comparisonAttempt":2}\n', before.decode().strip()):
                p.write_text(text)
                with self.assertRaises(ValueError):ledger(p)

    def test_stale_source_or_build_manifest_does_not_pass(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);v=prepare(root);jar=root/"backend/target/test.jar"
            with zipfile.ZipFile(jar,"w") as z:z.writestr("BOOT-INF/classes/closure-build.json",json.dumps(v))
            artifact(root,jar)
            m=dict(**v,evaluationSchemaVersion=2,evaluationSetVersion="closure-v2",evidenceKind="REAL_MODEL",inputFormatVersion=3,memoryFormatVersion=4,sampleCount=180,maxDiscreteCalls=360,promptVersion="social-v2.8",batchId="batch",samples=[dict(id=str(x)) for x in range(180)])
            p=root/"manifest.json";p.write_text(json.dumps(m))
            (root/"backend/target/m1-m5-evaluation-manifest.json").write_text(json.dumps(m))
            self.assertEqual("batch",version(root,p,jar)["batchId"])
            for field, old in (("promptVersion", "social-v2.7"), ("inputFormatVersion", 2), ("memoryFormatVersion", 3)):
                p.write_text(json.dumps({**m, field: old}))
                with self.assertRaisesRegex(ValueError, "INVALID_FROZEN_MANIFEST"):version(root,p,jar)
            p.write_text(json.dumps(m))
            m["batchId"]="other-batch";p.write_text(json.dumps(m))
            with self.assertRaises(ValueError):version(root,p,jar)
            m["buildId"]="different";p.write_text(json.dumps(m))
            with self.assertRaises(ValueError):version(root,p,jar)
            source=root/"backend/src/main/Test.java";source.parent.mkdir(parents=True);source.write_text("changed")
            with self.assertRaises(ValueError):version(root,p,jar)

    def test_schema_unread_is_unknown_and_known_missing_requires_migrations(self):
        r=migration_diff(ROOT,dict(status="NOT_RUN"));self.assertEqual("UNKNOWN",r["status"])
        self.assertEqual(3,len(r["evidence"]["migrations"]));self.assertIsNone(r["evidence"]["missingColumns"])
        r=migration_diff(ROOT,dict(status="PASS",columns=[],indexes=[]));self.assertEqual("FAIL",r["status"])
        self.assertTrue(all(m["status"]=="REQUIRED" for m in r["evidence"]["migrations"]))

    def test_collector_has_fixed_select_allowlist_and_no_rpc(self):
        source=(ROOT/"scripts/windows/support/ClosurePreflightData.java").read_text()
        self.assertIn('if(!ALLOWED.contains(sql))',source)
        self.assertNotIn('createStatement(',source)
        self.assertNotIn('.execute(',source)
        self.assertNotIn('setReadOnly(',source)
        for line in source.splitlines():
            if 'static final String ' in line:self.assertIn('= "SELECT ',line)

    def test_schema_positive_and_type_nullable_index_regressions(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);sql=root/"backend/sql";sql.mkdir(parents=True)
            (sql/"schema.sql").write_text('CREATE TABLE IF NOT EXISTS sample (id VARCHAR(64) NOT NULL PRIMARY KEY, diagnostics LONGTEXT NULL, INDEX idx_diag (diagnostics));')
            for name in ("20260912_game_realism_v2.sql","20260919_ai_turn_diagnostics.sql","20260922_milestone_closure.sql"):(sql/name).write_text('SELECT 1;')
            data=dict(status="PASS",columns=[dict(table_name="sample",column_name="id",column_type="varchar(64)",is_nullable="NO"),dict(table_name="sample",column_name="diagnostics",column_type="longtext",is_nullable="YES")],indexes=[dict(table_name="sample",index_name="PRIMARY",column_name="id",seq_in_index=1,non_unique=0),dict(table_name="sample",index_name="idx_diag",column_name="diagnostics",seq_in_index=1,non_unique=1)])
            self.assertEqual("PASS",migration_diff(root,data)["status"])
            for field,value in (("column_type","text"),("is_nullable","NO")):
                invalid=copy.deepcopy(data);invalid["columns"][1][field]=value
                self.assertEqual("FAIL",migration_diff(root,invalid)["status"])
            invalid=copy.deepcopy(data);invalid["indexes"][0]["non_unique"]=1
            self.assertEqual("FAIL",migration_diff(root,invalid)["status"])

    def test_unknowns_do_not_stop_independent_checks_and_no_login_or_rpc(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);env=directory/"private.env";env.write_text("ENV=local\nSECRET=never-export-this\n")
            matrix_file=directory/"matrix.yaml";matrix_file.write_text("fixture")
            journal=directory/"ledger";journal.write_text('{"event":"ATTEMPT_RESERVED","comparisonAttempt":1}\n')
            args=argparse.Namespace(root=str(ROOT),output=str(directory/"output"),environment=str(env),ledger=str(journal),manifest=str(directory/"missing"),jar="missing",matrix=str(matrix_file),matrix_invalid=False,accounts=None)
            with patch("closure_preflight.matrix",return_value=self.catalog),patch("closure_preflight.network",return_value=check("PASS","MOCK_TLS")),patch("closure_preflight.subprocess.run") as run,patch("builtins.print"):
                main(args);run.assert_not_called()
            text=(directory/"output/preflight.json").read_text();r=json.loads(text)
            self.assertNotIn("never-export-this",text);self.assertFalse(r["readyForExecution"])
            self.assertEqual("PASS",r["checks"]["comparisonLedger"]["status"])
            self.assertEqual("UNKNOWN",r["checks"]["caller"]["status"])
            self.assertEqual("NOT_RUN",r["checks"]["authenticatedRead"]["status"])
            draft=json.loads((directory/"output/execution-proposal.json").read_text())
            self.assertEqual("UNAUTHORIZED_EXECUTION_PROPOSAL",draft["documentKind"])
            self.assertFalse(draft["authorized"]);self.assertNotIn("approvedBy",draft)

    def test_bad_build_stops_database_even_when_target_matches(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);env=directory/"private.env";env.write_text('\n'.join(f'{k}={v}' for k,v in self.values.items()))
            m=directory/"matrix";m.write_text('fixture')
            args=argparse.Namespace(root=str(ROOT),output=str(directory/"output"),environment=str(env),ledger=str(directory/"missing"),manifest=str(directory/"missing"),jar="missing",matrix=str(m),matrix_invalid=False,accounts=None)
            with patch("closure_preflight.matrix",return_value=self.catalog),patch("closure_preflight.network",return_value=check("PASS","MOCK")),patch("closure_preflight.database_read") as db,patch("builtins.print"):
                main(args);db.assert_not_called()
            report=json.loads((directory/"output/preflight.json").read_text())
            self.assertEqual("VERSION_OR_BATCH_UNCONFIRMED",report["checks"]["database"]["reason"])

if __name__=="__main__":unittest.main()
