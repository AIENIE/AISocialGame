"""Offline protocol substitutes; no authentication helpers or paid calls."""
from copy import deepcopy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import werewolf_training_v2 as training


def run():
    return {"id": "run", "experimentId": "experiment", "operationId": "operation", "goal": "RULES_EVIDENCE",
            "status": "DRAFT", "stage": "PRACTICE", "configuration": {}, "tasks": [],
            "progress": {"totalTasks": 90, "completedTasks": 0, "failedTasks": 0, "developmentCases": 8,
                         "holdoutPairs": 32, "completedHoldoutPairs": 0, "gamePairs": 2, "completedGamePairs": 0},
            "cost": {"spentPoints": 233, "externalPoints": 1, "totalKnownPoints": 234, "budgetPoints": 3000,
                     "pendingCalls": 2, "totalCalls": 64, "actualCost": 2.33}, "assessment": {"status": "NOT_RUN"}}


def state():
    request = {"operationId": "operation", "goal": "RULES_EVIDENCE"}
    return {"schemaVersion": 2, "experimentId": "experiment", "sourceCommit": "fixture", "request": request,
            "requestHash": training.fingerprint(request), "events": [], "status": "INITIALIZED"}


def representative():
    value = run()
    value.update(status="PAUSED", reason="REPRESENTATIVE_CASE_READY")
    value["tasks"] = [{"id": kind, "caseId": "case-one", "kind": kind, "status": "SUCCEEDED", "result": {"content": kind}, "requestId": "request-" + kind}
                      for kind in ("PRACTICE", "TEACH")]
    return value


class FakeApi:
    def __init__(self, value=None):
        self.value = value
        self.posts = []
        self.lose = False
        self.saved_before_post = None
        self.diagnostics = {"matchId": "legacy", "status": "REVIEW_FAILED", "calls": [{"requestId": "old-unknown", "state": "RECONCILIATION_REQUIRED", "billedPoints": None}]}

    def __call__(self, method, path, body=None):
        if method == "GET":
            if path.endswith("/cases"): return {"items": [], "coverage": {}}
            if path.endswith("/strategies"): return {"items": [], "bindings": []}
            if "/matches?" in path: return {"items": [{"id": "legacy", "status": "REVIEW_FAILED"}], "total": 1}
            if path.endswith("/diagnostics"): return deepcopy(self.diagnostics)
            if path.endswith("/experiment"): return {"id": "experiment"}
            if self.value is None: raise training.Attention("API_HTTP_404")
            return deepcopy(self.value)
        self.posts.append((path, deepcopy(body)))
        if self.saved_before_post: self.saved_before_post()
        if path.endswith("/training-runs"):
            self.value = run()
            self.value["operationId"] = body["operationId"]
        else:
            self.value["status"] = "RUNNING"
        if self.lose:
            self.lose = False
            raise training.Attention("API_OUTCOME_UNKNOWN_LOST_RESPONSE")
        return deepcopy(self.value)


class TrainingTest(unittest.TestCase):
    def test_offline_initialization_freezes_identity_and_external_references(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            external = Path(folder) / "external.json"
            training.write_json(external, [{"requestId": "external-request", "budgetId": "external-budget"}])
            saved = training.initialize(SimpleNamespace(state=str(path), experiment_id="experiment", source_commit="commit", external_diagnostics=str(external)))
            self.assertTrue(path.exists())
            self.assertEqual(saved["request"]["externalDiagnostics"][0]["budgetId"], "external-budget")
            training.validate_state(saved)
            saved["request"]["operationId"] = "changed"
            with self.assertRaisesRegex(training.Attention, "SAVED_REQUEST_CHANGED"): training.validate_state(saved)

    def test_create_saves_before_send_and_recovers_lost_response_without_second_post(self):
        saved, api, snapshots = state(), FakeApi(), []
        api.lose = True
        api.saved_before_post = lambda: self.assertIn("createAttemptedAt", snapshots[-1])
        with self.assertRaisesRegex(training.Attention, "LOST_RESPONSE"):
            training.create(saved, api, lambda value: snapshots.append(deepcopy(value)))
        value = training.create(saved, api, lambda _: None)
        self.assertEqual(value["operationId"], "operation")
        self.assertEqual(len(api.posts), 1)

    def test_missing_original_uses_saved_id_and_never_generates_another_identity(self):
        saved, api = state(), FakeApi()
        training.create(saved, api, lambda _: None)
        self.assertEqual(api.posts[0][1], saved["request"])

    def test_old_unknown_costs_do_not_block_independently_authorized_work(self):
        saved, api = state(), FakeApi(run())
        training.advance(saved, api, lambda _: None)
        self.assertTrue(api.posts[0][0].endswith("/control/start-one"))
        self.assertTrue(saved["usagePending"])
        self.assertFalse(saved["settlementComplete"])

    def test_representative_requires_recorded_findings_and_exact_same_results(self):
        saved, api = state(), FakeApi(representative())
        training.poll(saved, api)
        with self.assertRaisesRegex(training.Attention, "REVIEW_REPRESENTATIVE"):
            training.control(saved, api, "resume", lambda _: None)
        training.acknowledge(saved, "agent", "Checked visible rules, answer, diagnosis, evidence, and suggested change.")
        api.value["tasks"][0]["result"] = {"content": "changed"}
        with self.assertRaisesRegex(training.Attention, "REVIEW_REPRESENTATIVE"):
            training.control(saved, api, "resume", lambda _: None)
        training.acknowledge(saved, "agent", "Reviewed changed result.")
        training.control(saved, api, "resume", lambda _: None)
        self.assertEqual(len(api.posts), 1)

    def test_representative_pause_is_not_automatically_resumed(self):
        saved, api = state(), FakeApi(representative())
        with self.assertRaisesRegex(training.Attention, "REPRESENTATIVE_CASE_READY"):
            training.advance(saved, api, lambda _: None)
        self.assertEqual(api.posts, [])

    def test_current_blocked_action_stops_while_ordinary_waiting_only_polls(self):
        saved, value = state(), run()
        value.update(status="RUNNING", tasks=[{"id": "task", "kind": "TEACH", "status": "WAITING"}])
        api = FakeApi(value)
        training.advance(saved, api, lambda _: None)
        self.assertEqual(api.posts, [])
        api.value["tasks"][0]["status"] = "BLOCKED"
        with self.assertRaisesRegex(training.Attention, "CURRENT_TASK"):
            training.advance(saved, api, lambda _: None)
        self.assertEqual(api.posts, [])

    def test_explicit_resume_repairs_only_confirmed_unsent_teacher_material_and_preserves_the_same_run(self):
        for kind in ("TEACH", "JUDGE"):
            with self.subTest(kind=kind):
                saved, value = state(), representative()
                value.update(reason="REPRESENTATIVE_CASE_FAILED", configuration={"representativeOnly": True})
                original_player = deepcopy(value["tasks"][0])
                value["tasks"][1].update(kind=kind, status="FAILED", reason="MATERIAL_TOO_LARGE", requestId=None,
                                         diagnostics={"calls": [], "inputCodePoints": 40001})
                api = FakeApi(value)
                with self.assertRaisesRegex(training.Attention, "REPRESENTATIVE_CASE_FAILED"):
                    training.advance(saved, api, lambda _: None)
                self.assertEqual(api.posts, [])
                result = training.control(saved, api, "resume", lambda _: None)
                self.assertEqual(api.posts, [(training.route(saved) + "/control/resume", None)])
                self.assertEqual(result["id"], "run")
                self.assertTrue(result["configuration"]["representativeOnly"])
                self.assertEqual(result["tasks"][0], original_player)
                self.assertEqual(saved["request"]["operationId"], "operation")
                self.assertTrue(saved["usagePending"])
                self.assertFalse(saved["settlementComplete"])

    def test_resume_material_whitelist_rejects_sent_unknown_unverified_and_other_failed_tasks(self):
        failure = {"id": "teach", "caseId": "case-one", "kind": "TEACH", "status": "FAILED",
                   "reason": "MATERIAL_TOO_LARGE", "requestId": None, "diagnostics": {"calls": []}}
        variants = [{"requestId": "original-request"},
                    {"diagnostics": {"calls": [{"requestId": "unknown", "state": "RECONCILIATION_REQUIRED"}]}},
                    {"diagnostics": {}}, {"kind": "PRACTICE"}, {"reason": "COACH_FORMAT_INVALID"},
                    {"status": "WAITING"}, {"status": "BLOCKED"}]
        for override in variants:
            with self.subTest(override=override):
                value = run()
                value.update(status="PAUSED", reason="REPRESENTATIVE_CASE_FAILED",
                             tasks=[deepcopy(failure), {**deepcopy(failure), "id": "other", **override}])
                api = FakeApi(value)
                with self.assertRaises(training.Attention):
                    training.control(state(), api, "resume", lambda _: None)
                self.assertEqual(api.posts, [])

    def test_cost_stop_includes_external_authoritative_points(self):
        value = run()
        value["cost"].update(spentPoints=2999, externalPoints=1, totalKnownPoints=3000)
        with self.assertRaisesRegex(training.Attention, "ACTUAL_COST_STOP_LINE"):
            training.admit(value)

    def test_control_response_loss_queries_original_and_does_not_replay(self):
        saved, api = state(), FakeApi(run())
        api.lose = True
        with self.assertRaisesRegex(training.Attention, "LOST_RESPONSE"):
            training.control(saved, api, "start-one", lambda _: None)
        self.assertEqual(saved["pendingControl"]["action"], "start-one")
        training.advance(saved, api, lambda _: None)
        self.assertNotIn("pendingControl", saved)
        self.assertEqual(len(api.posts), 1)

    def test_unknown_control_same_state_remains_unconfirmed(self):
        saved, api = state(), FakeApi(run())
        saved["pendingControl"] = {"action": "start-one"}
        with self.assertRaisesRegex(training.Attention, "CONTROL_OUTCOME_UNCONFIRMED"):
            training.advance(saved, api, lambda _: None)
        self.assertEqual(api.posts, [])

    def test_completed_training_with_old_unknown_is_not_settled_or_free(self):
        saved, value = state(), run()
        value.update(status="COMPLETED", stage="REPORT")
        training.capture(saved, value)
        self.assertTrue(saved["executionCompleted"])
        self.assertTrue(saved["usagePending"])
        self.assertFalse(saved["settlementComplete"])

    def test_export_preserves_failures_unknowns_and_separate_progress_without_mutation(self):
        saved, value = state(), run()
        value.update(status="PAUSED", reason="CURRENT_USAGE_UNKNOWN")
        value["tasks"] = [{"id": "failed-task", "caseId": "case-one", "kind": "TEACH", "status": "FAILED", "requestId": "new-unknown",
                           "diagnostics": {"calls": [{"requestId": "new-unknown", "state": "RECONCILIATION_REQUIRED", "billedPoints": None,
                                                        "timing": {"firstTextMs": 132000, "totalMs": 330000}}]}, "result": {"partial": "preserved"}}]
        api = FakeApi(value)
        with tempfile.TemporaryDirectory() as folder:
            report = training.export(saved, api, folder)
            text = (Path(folder) / "training-v2.md").read_text(encoding="utf-8")
            self.assertIn("实验累计实扣：233", text)
            self.assertIn("外部诊断计价：1", text)
            self.assertIn("待确认费用：2", text)
            self.assertIn("独立保留题完成配对：0/32", text)
            self.assertIn("完整对局完成配对：0/2", text)
            self.assertIn("132.000 秒", text)
            self.assertIn("没有本次共享策略可发布", text)
            self.assertIn("old-unknown", text)
            self.assertIn("preserved", text)
            self.assertEqual(report["run"]["tasks"][0]["status"], "FAILED")
            self.assertEqual(api.posts, [])


class PythonHelperProtocolTest(unittest.TestCase):
    def test_exact_sanitized_http_errors_are_preserved_but_unknown_messages_are_hidden(self):
        with tempfile.TemporaryDirectory() as directory:
            helper = Path(directory) / "helper.py"
            helper.write_text('def request(method, path, body=None):\n    raise RuntimeError(path)\n', encoding="utf-8")
            api = training.Api(helper)
            for code in ("API_HTTP_404", "API_HTTP_500"):
                with self.assertRaisesRegex(training.Attention, "^" + code + "$"):
                    api("GET", code)
            with self.assertRaisesRegex(training.Attention, "^API_OUTCOME_UNKNOWN_RuntimeError$"):
                api("GET", "private helper detail must not escape")


if __name__ == "__main__":
    unittest.main()
