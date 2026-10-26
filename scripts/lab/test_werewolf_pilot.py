"""Offline protocol substitutes; never import the private administrator helper."""
from copy import deepcopy
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import werewolf_pilot as pilot


class FakeApi:
    def __init__(self):
        self.detail = None
        self.matches = []
        self.posts = []
        self.lose = None
        self.commit_create = True
        self.strategies = {"strategyHeadId": "strategy-2", "items": [], "bindings": []}

    def __call__(self, method, path, body=None):
        if method == "GET":
            if path.startswith(pilot.PREFIX + "?"):
                items = [] if self.detail is None else [{"id": "experiment", "name": self.detail["name"]}]
                return {"items": items, "total": len(items)}
            if path == "/api/admin/lab/agents":
                return [{"id": f"profile-{number}", "personaId": f"ai{number}"} for number in range(1, 5)]
            if "/matches?" in path:
                return {"items": deepcopy(self.matches), "total": len(self.matches)}
            if path.endswith("/strategies"):
                return deepcopy(self.strategies)
            if "/scenario-evaluations/" in path:
                return deepcopy(self.detail["configuration"]["scenarioRuns"][path.rsplit("/", 1)[-1]])
            if path.endswith("/diagnostics"):
                return {"calls": []}
            return deepcopy(self.detail)
        self.posts.append((path, deepcopy(body)))
        if path == pilot.PREFIX:
            config = deepcopy(body)
            config["roster"] = [{"agentId": item["agentId"], "participantId": f"participant-{index}",
                                 "personaId": "ai" + item["agentId"][-1], "modelSelection": item["selection"]}
                                for index, item in enumerate(body["participants"])]
            result = {"id": "experiment", "name": body["name"], "status": "DRAFT", "budgetPoints": 3000,
                      "configuration": config, "cost": {"spentPoints": 0, "pendingCalls": 0, "totalCalls": 0}, "evaluations": []}
            if self.commit_create:
                self.detail = result
        elif path.endswith("/scenario-evaluations"):
            result = {"id": "scenario", "operationId": body["operationId"], "status": "RUNNING", "totalCalls": 40,
                      "candidateStrategyVersionId": body["candidateStrategyVersionId"], "nextIndex": 0, "results": []}
            self.detail["configuration"]["scenarioRuns"] = {body["operationId"]: result}
            self.detail["configuration"]["activeScenario"] = body["operationId"]
            self.detail["status"] = "PAUSED"
            self.detail.pop("reason", None)
        elif path.endswith("/evaluations"):
            result = {"id": "evaluation", "operationId": body["operationId"], "status": "RUNNING", "validPairs": 0, "completedGames": 0}
            self.detail["evaluations"].append(result)
            self.detail["status"] = "RUNNING"
        elif path.endswith("/control/start"):
            self.detail["status"] = "RUNNING"
            result = self.detail
        else:
            raise AssertionError(path)
        if self.lose and path.endswith(self.lose):
            self.lose = None
            raise pilot.Attention("API_OUTCOME_UNKNOWN_LOST_RESPONSE")
        return deepcopy(result)

    def training_done(self, count=2, checkpoint=False):
        self.matches = [{"id": f"training-{index}", "status": "REVIEWED", "variant": None} for index in range(count)]
        self.detail["status"] = "PAUSED" if checkpoint else "COMPLETED"
        if checkpoint:
            self.detail["reason"] = "TRAINING_CHECKPOINT_REACHED"

    def scenarios_done(self):
        run = next(iter(self.detail["configuration"]["scenarioRuns"].values()))
        run.update(status="COMPLETED", nextIndex=40,
                   results=[{"caseId": str(index), "variant": variant, "status": "SUCCEEDED"}
                            for index in range(20) for variant in ["BASELINE", "CANDIDATE"]],
                   calls=[{"requestId": f"scenario-{index}", "state": "SETTLED", "billedPoints": 1}
                          for index in range(40)])
        self.detail["configuration"].pop("activeScenario", None)


class PilotTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        folder = Path(self.directory.name)
        self.path = folder / "state.json"
        plan = folder / "plan.json"
        pilot.write_json(plan, [{"personaId": "ai1", "targetRole": "WEREWOLF", "randomSeed": 99}])
        args = SimpleNamespace(state=self.path, evaluation_plan=plan, source_commit="test-only", seed=123,
                               base_agent_ids=[f"profile-{number}" for number in range(1, 5)])
        pilot.initialize(args)
        self.api = FakeApi()
        self.runner = pilot.Pilot(self.path, self.api)

    def test_lost_creation_recovers_original_without_duplicate(self):
        self.api.lose = "/experiments"
        with self.assertRaises(pilot.Attention):
            self.runner.create()
        restarted = pilot.Pilot(self.path, self.api)
        self.assertEqual("experiment", restarted.create()["experimentId"])
        self.assertEqual(1, len(self.api.posts))

    def test_export_fetches_scenario_call_receipts_and_preserves_unknown_cost(self):
        self.runner.create()
        operation = self.runner.state["scenario"]["operationId"]
        self.api.detail["configuration"]["scenarioRuns"] = {operation: {"id": "scenario", "operationId": operation, "status": "COMPLETED", "calls": [
            {"requestId": "settled", "budgetId": "budget-1", "state": "SETTLED", "billedPoints": 7, "billedCost": "0.000001", "modelKey": "fixture"},
            {"requestId": "unknown", "budgetId": "budget-2", "state": "UNCONFIRMED", "billedPoints": None, "billedCost": None, "modelKey": "fixture"}]}}
        self.api.detail["cost"].update(spentPoints=7, pendingCalls=1)
        receipt = self.runner.export()
        import json
        document = json.loads(Path(receipt["json"]).read_text(encoding="utf-8"))
        self.assertEqual(2, len(document["scenarioCalls"]))
        self.assertIsNone(document["scenarioCalls"][1]["billedPoints"])
        self.assertEqual({"uniqueRequests": 2, "knownBilledPoints": 7, "unconfirmedRequests": 1, "authoritativeSpentPoints": 7, "authoritativePendingCalls": 1, "knownPointsDifference": 0}, document["callSummary"])
        markdown = Path(receipt["markdown"]).read_text(encoding="utf-8")
        self.assertIn("独立情境调用记录 2 笔", markdown)
        self.assertIn("费用待确认记录 1 笔", markdown)
        self.assertNotIn("matchCalls不含独立情境", markdown)
        self.assertEqual(1, len(self.api.posts))

    def test_unknown_creation_without_match_never_posts_again(self):
        self.api.lose = "/experiments"
        self.api.commit_create = False
        with self.assertRaises(pilot.Attention):
            self.runner.create()
        with self.assertRaisesRegex(pilot.Attention, "NO_DUPLICATE"):
            pilot.Pilot(self.path, self.api).create()
        self.assertEqual(1, len(self.api.posts))

    def test_checkpoint_preserves_actual_count_and_scenario_identity_after_loss(self):
        self.runner.create()
        self.api.training_done(1, checkpoint=True)
        self.api.lose = "/scenario-evaluations"
        with self.assertRaises(pilot.Attention):
            self.runner.advance()
        restarted = pilot.Pilot(self.path, self.api)
        self.assertEqual("SCENARIOS_IN_PROGRESS", restarted.advance()["status"])
        self.assertEqual(1, restarted.state["actualTrainingGames"])
        self.assertTrue(restarted.state["trainingCheckpointReached"])
        self.assertEqual(1, sum(path.endswith("/scenario-evaluations") for path, _ in self.api.posts))

    def test_lost_evaluation_response_waits_for_original_inflight_call(self):
        self.runner.create()
        self.api.training_done()
        self.runner.advance()
        self.api.scenarios_done()
        self.api.lose = "/evaluations"
        with self.assertRaises(pilot.Attention):
            self.runner.advance()
        self.api.detail["cost"]["pendingCalls"] = 1
        restarted = pilot.Pilot(self.path, self.api)
        self.assertEqual("PAIRED_GAMES_IN_PROGRESS", restarted.advance()["status"])
        self.assertEqual(1, sum(path.endswith("/evaluations") for path, _ in self.api.posts))

    def test_actual_budget_or_budget_pause_prevents_next_stage(self):
        self.runner.create()
        self.api.training_done()
        self.api.detail["cost"]["spentPoints"] = 3000
        with self.assertRaisesRegex(pilot.Attention, "BUDGET"):
            self.runner.advance()
        self.api.detail["cost"].update(spentPoints=20, pendingCalls=1)
        self.api.detail["budgetPaused"] = True
        with self.assertRaisesRegex(pilot.Attention, "BUDGET"):
            self.runner.advance()
        self.assertEqual(1, len(self.api.posts))

    def test_authorized_replacement_review_allows_independent_stages_with_old_fee_pending(self):
        self.runner.create()
        self.api.training_done()
        self.api.matches[0]["status"] = "REVIEW_FAILED"
        self.api.detail["cost"].update(spentPoints=200, pendingCalls=1)
        with self.assertRaisesRegex(pilot.Attention, "MATCH_OR_REVIEW_FAILED"):
            self.runner.advance()
        self.assertEqual(1, len(self.api.posts))
        # External, explicitly authorized replacement succeeded. The old request's
        # unknown fee remains in the authoritative aggregate; this runner never retries it.
        self.api.matches[0]["status"] = "REVIEWED"
        self.assertEqual("SCENARIOS_STARTED_OR_RECOVERED", self.runner.advance()["status"])
        self.api.scenarios_done()
        self.assertEqual("PAIR_STARTED_OR_RECOVERED", self.runner.advance()["status"])
        self.api.detail["evaluations"][0].update(status="COMPLETED", validPairs=1, completedGames=2)
        self.api.detail["status"] = "COMPLETED"
        result = self.runner.advance()
        self.assertEqual("PILOT_EXECUTION_COMPLETED_USAGE_PENDING", result["status"])
        self.assertFalse(result["settlementComplete"])
        self.assertEqual(1, result["cost"]["pendingCalls"])
        self.assertTrue(self.runner.state["executionCompleted"])
        restarted = pilot.Pilot(self.path, self.api)
        self.assertFalse(restarted.state["settlementComplete"])
        with patch.object(pilot, "Api", return_value=self.api), patch.object(pilot.sys, "argv", [
                "werewolf_pilot.py", "run", "--state", str(self.path), "--api-helper", "unused.py"]), \
                patch.object(pilot.time, "sleep") as sleep, redirect_stdout(StringIO()):
            self.assertEqual(0, pilot.main())
        sleep.assert_not_called()
        self.assertEqual(3, len(self.api.posts))
        self.api.detail["cost"]["pendingCalls"] = 0
        self.assertTrue(restarted.advance()["settlementComplete"])
        self.assertEqual(3, len(self.api.posts))

    def test_current_scenario_unknown_result_never_starts_another_stage(self):
        self.runner.create()
        self.api.training_done()
        self.runner.advance()
        run = next(iter(self.api.detail["configuration"]["scenarioRuns"].values()))
        run.update(status="PAUSED", reason="LAB_CALL_UNCERTAIN")
        self.api.detail["cost"]["pendingCalls"] = 1
        with self.assertRaisesRegex(pilot.Attention, "SCENARIO_PAUSED_LAB_CALL_UNCERTAIN"):
            self.runner.advance()
        self.api.scenarios_done()
        run["calls"][0].update(state="USAGE_UNKNOWN", billedPoints=None)
        with self.assertRaisesRegex(pilot.Attention, "SCENARIO_UNCONFIRMED_USAGE"):
            self.runner.advance()
        run["calls"][0].update(state="SETTLED", billedPoints=1)
        run["results"][0]["status"] = "RUNNING"
        with self.assertRaisesRegex(pilot.Attention, "SCENARIO_RESULTS_UNCONFIRMED"):
            self.runner.advance()
        self.assertEqual(2, len(self.api.posts))

    def test_finished_inflight_overspend_can_finish_without_new_call(self):
        self.runner.create()
        self.api.training_done()
        self.runner.advance()
        self.api.scenarios_done()
        self.runner.advance()
        self.api.detail["evaluations"][0].update(status="COMPLETED", validPairs=1, completedGames=2)
        self.api.detail["status"] = "COMPLETED"
        self.api.detail["cost"]["spentPoints"] = 3002
        self.assertEqual("PILOT_EXECUTION_COMPLETED", self.runner.advance()["status"])
        self.assertEqual(3, len(self.api.posts))

    def test_failed_scenario_is_not_automatically_regenerated(self):
        self.runner.create()
        self.api.training_done()
        self.runner.advance()
        self.api.scenarios_done()
        next(iter(self.api.detail["configuration"]["scenarioRuns"].values()))["results"][0]["status"] = "FAILED"
        with self.assertRaisesRegex(pilot.Attention, "SCENARIO_FAILURES"):
            self.runner.advance()
        self.assertEqual(2, len(self.api.posts))

    def test_explicit_failure_review_allows_planned_pair_but_preserves_failure(self):
        self.runner.create()
        self.api.training_done()
        self.runner.advance()
        self.api.scenarios_done()
        results = next(iter(self.api.detail["configuration"]["scenarioRuns"].values()))["results"]
        results[0]["status"] = "FAILED"
        self.api.detail["cost"]["pendingCalls"] = 1
        run = next(iter(self.api.detail["configuration"]["scenarioRuns"].values()))
        run["calls"][0].update(state="USAGE_UNKNOWN", billedPoints=None)
        with self.assertRaisesRegex(pilot.Attention, "UNCONFIRMED_USAGE"):
            self.runner.acknowledge_scenario_failures("reviewer", "Reviewed failure, continue planned pair")
        run["calls"][0].update(state="NOT_SENT", billedPoints=0)
        # All scenario requests are resolved; one unrelated historical coach fee remains unknown.
        receipt = self.runner.acknowledge_scenario_failures("reviewer", "Reviewed failure, continue planned pair")
        self.assertEqual(0, receipt["paidCallsStarted"])
        self.assertEqual(2, len(self.api.posts))
        self.assertEqual("PAIR_STARTED_OR_RECOVERED", self.runner.advance()["status"])
        self.assertEqual(1, self.api.detail["cost"]["pendingCalls"])
        self.assertEqual("FAILED", results[0]["status"])
        results[1]["status"] = "FAILED"
        with self.assertRaisesRegex(pilot.Attention, "SCENARIO_FAILURES"):
            self.runner.advance()

    def test_markdown_exports_counter_examples_paired_uncertainty_and_true_source_count(self):
        self.runner.create()
        card = {"id": "card", "role": "SEER", "condition": "condition", "advice": "advice", "exceptions": "", "evidence": [{"jobId": "support", "actorId": "actor", "eventIds": ["support-event"]}]}
        self.api.strategies["items"] = [
            {"id": "strategy-1", "sequenceNumber": 1, "content": {"cards": [deepcopy(card)]}},
            {"id": "strategy-2", "previousId": "strategy-1", "sequenceNumber": 2, "content": {"cards": [{**card, "counterEvidence": [{"jobId": "counter", "actorId": "actor", "eventIds": ["counter-event"]}]}]}}]
        self.api.detail["configuration"]["scenarioManifest"] = [{"caseId": str(index), "sourceGroup": "family-" + str(index % 5)} for index in range(20)]
        self.api.detail["evaluations"] = [{"id": "pair", "status": "RUNNING", "targetRole": "SEER", "participantId": "copy",
            "baseline": {"attemptedGames": 5, "unfinishedGames": 1, "failedGames": 0}, "candidate": {"attemptedGames": 5, "unfinishedGames": 1, "failedGames": 0},
            "pairedOutcomes": {"sampleSize": 4, "candidateOnlyWins": 2, "baselineOnlyWins": 1, "sameOutcome": 1, "difference": .25,
                               "interval95": {"lower": -1, "upper": 1, "method": "HOEFFDING_PAIRED_CONSERVATIVE"}}}]
        receipt = self.runner.export()
        markdown = Path(receipt["markdown"]).read_text(encoding="utf-8")
        self.assertNotIn("反例证据", markdown.split("### v2")[0])
        for expected in ["counter-event", "反例变化：前版 0 项 → 当前 1 项", "构造来源：5 种", "完整配对数：4", "未结束 1", "25.0 个百分点", "[-100.0, 100.0]", "假设各配对相互独立", "先导样本不足以证明能力提升"]:
            self.assertIn(expected, markdown)
        self.assertEqual(1, len(self.api.posts))


if __name__ == "__main__":
    unittest.main()
