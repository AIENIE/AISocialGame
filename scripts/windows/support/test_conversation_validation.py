import copy
import unittest
from pathlib import Path
import tempfile
import json
from conversation_validation import *

class ConversationValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root=Path(__file__).resolve().parents[3]/"backend/target/conversation-validation-mock"
        # Produced by real rule submissions in ConversationValidationTest; no service or model.
        cls.m,cls.b=frozen(root/"conversation-manifest.json",root/"conversation-bundle.json")
        cls.e=load_json(root/"evidence.json")
        cls.r={k:cls.m[k] for k in HEADER};cls.r["evidenceSha256"]="synthetic-hash";cls.r["samples"]=[]
        for row in cls.e["samples"]:
            ref=[dict(sampleId=row["id"],pointer="/decision/action/type")]
            cls.r["samples"].append(dict(id=row["id"],visibleInformation="NO_OBSERVED_VIOLATION",scores={d:4 for d in DIMENSIONS},
                evidence={d:ref for d in DIMENSIONS},scoreReasons={d:"Synthetic algorithm check only" for d in DIMENSIONS},conversation={c:dict(verdict="PASS",reason="Synthetic validator test only",evidence=ref) for c in CHECKS}))
    def setUp(self): self.m,self.b,self.e,self.r=map(copy.deepcopy,(self.__class__.m,self.__class__.b,self.__class__.e,self.__class__.r))
    def result(self): return measure(self.m,self.b,self.e,self.r,"synthetic-hash")
    def test_complete_synthetic_passes_algorithm_never_real_quality(self):
        result=self.result();self.assertTrue(result["algorithmPassed"],result["problems"]);self.assertFalse(result["qualityPassed"]);self.assertFalse(result["L4Passed"])
    def test_relabeling_mock_without_rpc_receipts_does_not_pass(self):
        for document in (self.m,self.b,self.e,self.r):document["evidenceKind"]="REAL_MODEL"
        result=self.result();self.assertFalse(result["qualityPassed"]);self.assertTrue(any('RPC_RESERVATIONS_MISSING' in p for p in result['problems']))
    def test_missing_wrong_fallback_status_and_scope_rejected(self):
        for value in (None,0,"false",True):
            with self.subTest(value=value):
                self.e=copy.deepcopy(self.__class__.e);self.e["samples"][0]["decision"]["fallback"]=value
                with self.assertRaises(ValueError):self.result()
    def test_fallback_keeps_denominator_and_excludes_normal_scores(self):
        row=self.e["samples"][0];row["status"]="LOCAL_FALLBACK";row["decision"]["fallback"]=True
        result=self.result();self.assertFalse(result["algorithmPassed"]);self.assertEqual(48,result["groups"]["SINGLE"]["planned"]);self.assertEqual(47,result["groups"]["SINGLE"]["normal"])
    def test_verdict_reason_reference_and_score_type(self):
        for change in (dict(verdict="FAIL"),dict(verdict="INSUFFICIENT_EVIDENCE"),dict(reason="  "),dict(evidence=[dict(sampleId="missing",pointer="/decision/action/type")])):
            self.r=copy.deepcopy(self.__class__.r);self.r["samples"][0]["conversation"][CHECKS[0]].update(change);self.assertFalse(self.result()["algorithmPassed"])
        self.r=copy.deepcopy(self.__class__.r);self.r["samples"][0]["scores"][DIMENSIONS[0]]=True;self.assertFalse(self.result()["algorithmPassed"])
    def test_hash_input_apply_and_sequence_tampering(self):
        self.r["evidenceSha256"]="changed";self.assertFalse(self.result()["algorithmPassed"])
        self.r=copy.deepcopy(self.__class__.r);self.e["samples"][0]["input"]={};self.assertFalse(self.result()["algorithmPassed"])
        self.e=copy.deepcopy(self.__class__.e)
        row=next(r for r in self.e["samples"] if r["stepId"]=="ACTION");row["coverage"]["status"]="NOT_APPLIED";self.assertFalse(self.result()["algorithmPassed"])
    def test_inventory_duplicates_versions_and_resume_metadata(self):
        self.m["samples"].append(self.m["samples"][0])
        with self.assertRaises(ValueError):inventory(self.m)
        self.m=copy.deepcopy(self.__class__.m);self.m["evaluationSetVersion"]="closure-v2"
        with self.assertRaises(ValueError):inventory(self.m)
        self.m=copy.deepcopy(self.__class__.m);self.e["samples"][0]["personaId"]="ai4"
        with self.assertRaises(ValueError):resume_check(self.m,self.e)
    def test_pilot_uses_same_evidence_checks_and_blocks_nonpilot_attempt(self):
        ids=set(self.m["pilotIds"]);self.r["samples"]=[r for r in self.r["samples"] if r["id"] in ids]
        self.e=copy.deepcopy(self.__class__.e)
        self.e["samples"]=[r if r["id"] in ids else dict(next(m for m in self.m["samples"] if m["id"]==r["id"]),status="NOT_RUN") for r in self.e["samples"]]
        self.assertTrue(measure(self.m,self.b,self.e,self.r,"synthetic-hash",True)["algorithmPassed"])
        next(r for r in self.e["samples"] if r["id"] not in ids)["status"]="NO_RESPONSE"
        self.assertFalse(measure(self.m,self.b,self.e,self.r,"synthetic-hash",True)["algorithmPassed"])
    def test_unapproved_proposal_cannot_authorize(self):
        with self.assertRaises(ValueError):grant_check(self.m,dict(authorizationStatus="NOT_AUTHORIZED"),"unused","unused","unused","hash",74,"PILOT")
    def test_empty_batch_has_zero_actual_attempts_without_fabricated_history(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'new.jsonl';p.write_bytes(b'')
            self.assertEqual(conversation_ledger(p),dict(consumed=0,sha256=sha(p)))
            self.assertEqual(p.read_bytes(),b'')
    def test_authorization_binds_artifacts_prefix_cap_expiry_and_caller_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);journal=root/'journal.jsonl';manifest=root/'manifest.json';bundle=root/'bundle.json'
            journal.write_bytes(b'{"comparisonAttempt":1}\n');manifest.write_text('{}');bundle.write_text('{}')
            g=dict(authorizationStatus="APPROVED",approvedBy="synthetic",approvalReference="test-only",callerId="aisocialgame",callerRecordId=37,callerLifecycle="SHARED",enableOriginalCaller=False,disableCallerOnExit=False,
                expiresAt="2099-01-01T00:00:00Z",model="deepseek-flash",comparisonLedger=str(journal),manifestSha256=sha(manifest),bundleSha256=sha(bundle),artifactSha256="jar",
                baselineConsumed=1,cumulativeComparisonLimit=193,baselineLedgerSha256=sha(journal),**{k:self.m[k] for k in ("batchId","buildId","sourceFingerprint")})
            grant_check(self.m,g,journal,manifest,bundle,"jar",1,"PILOT")
            for change in (dict(cumulativeComparisonLimit=194),dict(cumulativeComparisonLimit=True),dict(expiresAt="2000-01-01T00:00:00Z"),dict(enableOriginalCaller=True),dict(disableCallerOnExit=True),dict(callerRecordId=0),dict(manifestSha256="bad"),dict(baselineLedgerSha256="bad"),dict(buildId="bad")):
                with self.subTest(change=change),self.assertRaises(ValueError):grant_check(self.m,dict(g,**change),journal,manifest,bundle,"jar",1,"PILOT")

if __name__=="__main__":unittest.main()
