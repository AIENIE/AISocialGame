#!/usr/bin/env python3
"""Resumable local werewolf pilot. No credentials or login implementation here.

Initialize offline with four profile IDs (in ai1..ai4 order) and a JSON plan:
  [{"personaId":"ai1","targetRole":"WEREWOLF","randomSeed":202610261}, ...]
The explicit plan may contain one to four paired evaluations. Each pair changes
only the designated participant's strategy. It is not a strength certification.

  python scripts/lab/werewolf_pilot.py init --state <outside-repo>/pilot.json \
    --base-agent-ids <ai1-profile> <ai2-profile> <ai3-profile> <ai4-profile> \
    --evaluation-plan <outside-repo>/plan.json --source-commit <commit>
  python scripts/lab/werewolf_pilot.py create --state <pilot.json> --api-helper <admin-local.ps1>
  python scripts/lab/werewolf_pilot.py advance --state <pilot.json> --api-helper <admin-local.ps1>

create creates one draft; advance starts at most one stage per invocation.
poll and export never start work. run repeats advance for a bounded time (50s
by default); it stops on every unexpected pause, current-action unknown outcome,
or actual-spend budget limit. Historical pending fees remain reported separately
and do not block an independently authorized next stage. Reinvoke after
inspecting the saved state. There is no automatic paid
review retry, budget change, publication, or restart of an unknown sent action.
After reviewing settled scenario failures, an operator may explicitly record
acknowledge-scenario-failures --reviewed-by <name> --review-notes <assessment>.
This preserves failures and permits the planned paired games; it does not rerun
failed scenarios or certify their answers. Changed results require a new review.

The external helper can be a .ps1 taking ApiPath/Method/BodyFile and returning
JSON, or a .py exposing request(method, path, body=None) -> decoded JSON.
Only pass the approved local administrator helper; it owns authentication.
"""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import tempfile
import time
import uuid

ORIGIN = "https://localsocialgame.testhut.top"
PREFIX = "/api/admin/lab/experiments"
PLAYER = {"modelKey": "deepseek-flash", "thinkingMode": "ENABLED", "thinkingEffort": "LOW"}
COACH = {"modelKey": "gpt-6.1-sol", "thinkingMode": "ENABLED", "thinkingEffort": "MAX"}
ROLES = {"WEREWOLF", "VILLAGER", "SEER", "WITCH", "HUNTER"}
EXECUTION_DONE = {"PILOT_EXECUTION_COMPLETED", "PILOT_EXECUTION_COMPLETED_USAGE_PENDING"}


class Attention(RuntimeError):
    """A saved, actionable stop; never a reason to create a new paid request."""


def now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


@contextmanager
def exclusive_state(path):
    """OS lock is released even if a runner crashes; the file contains no secret."""
    lock = Path(str(path) + ".lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("a+b") as stream:
        stream.seek(0)
        if not stream.read(1):
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


class Api:
    def __init__(self, helper):
        self.helper = Path(helper).resolve(strict=True)
        self.module = None
        if self.helper.suffix == ".py":
            spec = importlib.util.spec_from_file_location("private_lab_api", self.helper)
            self.module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.module)
        elif self.helper.suffix != ".ps1":
            raise Attention("API_HELPER_MUST_BE_PY_OR_PS1")

    def __call__(self, method, path, body=None):
        # Never print helper stderr: authentication helpers may include private data.
        try:
            if self.module:
                return self.module.request(method, path, body)
            with tempfile.TemporaryDirectory(prefix="werewolf-pilot-") as directory:
                command = ["pwsh", "-NoProfile", "-File", str(self.helper), "-ApiPath", path, "-Method", method]
                if body is not None:
                    payload = Path(directory) / "request.json"
                    payload.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
                    command.extend(["-BodyFile", str(payload)])
                result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8-sig", errors="replace", timeout=100, check=False)
                if result.returncode:
                    status = re.search(r"HTTP (\d{3})", result.stderr)
                    raise Attention("API_HTTP_" + status[1] if status else "API_HELPER_FAILED")
                return json.loads(result.stdout)
        except Attention:
            raise
        except Exception as error:
            raise Attention("API_OUTCOME_UNKNOWN_" + type(error).__name__) from None


def initialize(args):
    if args.state.exists():
        raise Attention("STATE_ALREADY_EXISTS")
    if not args.base_agent_ids or len(set(args.base_agent_ids)) != 4 or not args.evaluation_plan or not args.source_commit:
        raise Attention("INIT_REQUIRES_FOUR_PROFILES_EVALUATION_PLAN_AND_SOURCE_COMMIT")
    plan = json.loads(args.evaluation_plan.read_text(encoding="utf-8-sig"))
    if not isinstance(plan, list) or not 1 <= len(plan) <= 4:
        raise Attention("EVALUATION_PLAN_REQUIRES_ONE_TO_FOUR_PAIRS")
    for item in plan:
        if set(item) != {"personaId", "targetRole", "randomSeed"} or item["personaId"] not in ["ai1", "ai2", "ai3", "ai4"] or item["targetRole"] not in ROLES or type(item["randomSeed"]) is not int:
            raise Attention("INVALID_EXPLICIT_EVALUATION_PLAN")
    randomizer = random.Random(args.seed)
    slots = args.base_agent_ids + [randomizer.choice(args.base_agent_ids) for _ in range(5)]
    randomizer.shuffle(slots)
    run_id = str(uuid.uuid4())
    configuration = {
        "name": "狼人杀共享策略先导 · " + run_id,
        "gameId": "werewolf", "learningMode": "SHARED_STRATEGY",
        "playerCount": 9, "targetGames": 2, "budgetPoints": 3000,
        "randomSeed": args.seed, "trainingCheckpointPoints": 900,
        "rules": {"template": "standard", "playerCount": 9, "witchRule": "first_night", "winCondition": "side", "speechTime": 60, "hasLastWords": "first_night", "aiDifficulty": 2},
        "participants": [{"agentId": agent, "selection": PLAYER.copy()} for agent in slots], "coach": COACH.copy(),
    }
    manifest = {"configuration": configuration, "evaluationPlan": plan,
                "baseProfiles": dict(zip(["ai1", "ai2", "ai3", "ai4"], args.base_agent_ids)), "sourceCommit": args.source_commit}
    value = {"schemaVersion": 1, "runId": run_id, "manifest": manifest, "manifestHash": fingerprint(manifest),
             "createdAt": now(), "experimentId": None, "createAttempted": False,
             "scenario": {"operationId": str(uuid.uuid4()), "attempted": False},
             "evaluations": [{"operationId": str(uuid.uuid4()), "attempted": False, "plan": item} for item in plan]}
    write_json(args.state, value)
    return {"state": str(args.state), "runId": run_id, "status": "INITIALIZED_OFFLINE", "paidCalls": 0}


class Pilot:
    def __init__(self, path, api):
        self.path, self.api = Path(path), api
        self.state = json.loads(self.path.read_text(encoding="utf-8-sig"))
        if self.state.get("schemaVersion") != 1 or fingerprint(self.state["manifest"]) != self.state["manifestHash"]:
            raise Attention("MANIFEST_CHANGED_OR_UNSUPPORTED")

    def save(self):
        self.state["updatedAt"] = now()
        write_json(self.path, self.state)

    def pages(self, path):
        rows = []
        for page in range(10000):
            value = self.api("GET", f"{path}?page={page}&size=50")
            rows.extend(value["items"])
            if len(rows) >= value["total"]:
                return rows
            if not value["items"]:
                break
        raise Attention("INCOMPLETE_PAGINATED_RESULT")

    def endpoint(self, suffix=""):
        return PREFIX + "/" + self.state["experimentId"] + suffix

    def validate_detail(self, detail):
        expected = self.state["manifest"]["configuration"]
        config = detail["configuration"]
        if detail["budgetPoints"] != 3000 or config.get("gameId") != "werewolf" or config.get("learningMode") != "SHARED_STRATEGY":
            raise Attention("RUN_CONFIGURATION_CHANGED")
        for key in ["playerCount", "targetGames", "randomSeed", "trainingCheckpointPoints", "rules"]:
            if config.get(key) != expected[key]:
                raise Attention("RUN_CONFIGURATION_CHANGED_" + key)
        for key, value in COACH.items():
            if config["coach"].get(key) != value:
                raise Attention("COACH_CONFIGURATION_CHANGED")
        if len(config["roster"]) != 9:
            raise Attention("ROSTER_CHANGED")
        for actual, wanted in zip(config["roster"], expected["participants"]):
            if actual["agentId"] != wanted["agentId"] or any(actual["modelSelection"].get(key) != value for key, value in PLAYER.items()):
                raise Attention("PLAYER_CONFIGURATION_CHANGED")

    def create(self):
        if not self.state["experimentId"]:
            found = [item for item in self.pages(PREFIX) if item["name"] == self.state["manifest"]["configuration"]["name"]]
            if len(found) > 1:
                raise Attention("AMBIGUOUS_CREATION_RECOVERY")
            if found:
                self.state["experimentId"] = found[0]["id"]
                self.save()
            elif self.state["createAttempted"]:
                raise Attention("ORIGINAL_CREATION_UNKNOWN_NO_DUPLICATE_CREATED")
            else:
                agents = {item["id"]: item for item in self.api("GET", "/api/admin/lab/agents")}
                if any(agents.get(agent_id, {}).get("personaId") != persona for persona, agent_id in self.state["manifest"]["baseProfiles"].items()):
                    raise Attention("BASE_PROFILE_PERSONAS_DO_NOT_MATCH_AI1_TO_AI4")
                self.state["createAttempted"] = True
                self.save()  # Marker is durable before the non-idempotent create request.
                result = self.api("POST", PREFIX, self.state["manifest"]["configuration"])
                self.state["experimentId"] = result["id"]
                self.save()
        detail = self.api("GET", self.endpoint())
        self.validate_detail(detail)
        return {"status": "DRAFT_FOUND_OR_CREATED", "experimentId": detail["id"], "url": ORIGIN + "/admin/lab/" + detail["id"]}

    def snapshot(self):
        if not self.state["experimentId"]:
            raise Attention("CREATE_OR_RECOVER_DRAFT_FIRST")
        detail = self.api("GET", self.endpoint())
        self.validate_detail(detail)
        matches = self.pages(self.endpoint("/matches"))
        summary = {"experimentId": detail["id"], "status": detail["status"], "reason": detail.get("reason"),
                   "cost": detail["cost"], "matches": [{key: row.get(key) for key in ["id", "sequenceNumber", "status", "reason", "variant", "archiveId"]} for row in matches],
                   "scenarios": {key: {field: value.get(field) for field in ["status", "nextIndex", "reason"]} for key, value in detail["configuration"].get("scenarioRuns", {}).items()},
                   "evaluations": [{key: value.get(key) for key in ["operationId", "status", "completedGames", "validPairs"]} for value in detail.get("evaluations", [])]}
        if fingerprint(summary) != self.state.get("progressHash"):
            self.state["progressHash"] = fingerprint(summary)
            self.state["lastProgressEpoch"] = time.time()
        self.state["progress"] = summary
        self.save()
        return detail, matches

    @staticmethod
    def admit(detail):
        if detail["cost"]["spentPoints"] >= 3000 or detail.get("budgetPaused"):
            raise Attention("BUDGET_STOP_LINE_REACHED")

    def completed_scenario(self, operation_id):
        # This receipt is scoped to the scenario, unlike the experiment cost total.
        scenario = self.api("GET", self.endpoint("/scenario-evaluations/" + operation_id))
        if scenario.get("status") != "COMPLETED":
            raise Attention("SCENARIO_NOT_COMPLETED_INSPECT_ORIGINAL_REQUEST")
        if any(row.get("status") not in ["SUCCEEDED", "FAILED"] for row in scenario.get("results", [])):
            raise Attention("SCENARIO_RESULTS_UNCONFIRMED_INSPECT_ORIGINAL_REQUEST")
        if not isinstance(scenario.get("calls"), list):
            raise Attention("SCENARIO_CALL_RECEIPTS_UNAVAILABLE")
        if any(call.get("state") not in ["SETTLED", "NOT_SENT"] or call.get("billedPoints") is None for call in scenario["calls"]):
            raise Attention("SCENARIO_UNCONFIRMED_USAGE_INSPECT_ORIGINAL_REQUEST")
        return scenario

    def advance(self):
        detail, matches = self.snapshot()
        reason = detail.get("reason")
        if detail.get("reviewFailed") or any(row["status"] in ["ABORTED", "REVIEW_FAILED"] for row in matches):
            raise Attention("MATCH_OR_REVIEW_FAILED_INSPECT_ORIGINAL_REQUEST")
        if reason and reason != "TRAINING_CHECKPOINT_REACHED":
            raise Attention("EXPERIMENT_REQUIRES_ATTENTION_" + str(reason))
        if detail["status"] in ["STOPPED", "STOPPING", "PAUSING"]:
            raise Attention("EXPERIMENT_" + detail["status"])
        config = detail["configuration"]
        scenario_op = self.state["scenario"]["operationId"]
        runs = config.get("scenarioRuns", {})
        if config.get("activeScenario") and config["activeScenario"] != scenario_op:
            raise Attention("UNEXPECTED_SCENARIO_OPERATION")
        training = [row for row in matches if not row.get("variant") or row["variant"] == "TRAINING"]
        if len(training) > 2:
            raise Attention("UNEXPECTED_EXTRA_TRAINING_GAME")
        training_ready = bool(training) and all(row["status"] == "REVIEWED" and not row.get("reason") for row in training)
        if not self.state.get("candidateStrategyVersionId"):
            if detail["status"] == "DRAFT":
                self.admit(detail)
                self.api("POST", self.endpoint("/control/start"))
                return {"status": "TRAINING_STARTED"}
            if not training_ready or (len(training) < 2 and reason != "TRAINING_CHECKPOINT_REACHED"):
                if detail["status"] == "PAUSED":
                    raise Attention("TRAINING_PAUSED_INSPECT_BEFORE_RESUME")
                return self.waiting("TRAINING_IN_PROGRESS")
            strategies = self.api("GET", self.endpoint("/strategies"))
            if not strategies.get("strategyHeadId"):
                raise Attention("TRAINING_HAS_NO_PUBLISHED_STRATEGY_VERSION")
            self.state["candidateStrategyVersionId"] = strategies["strategyHeadId"]
            self.state["actualTrainingGames"] = len(training)
            self.state["trainingCheckpointReached"] = reason == "TRAINING_CHECKPOINT_REACHED"
            self.save()
        if not self.state["scenario"].get("request"):
            self.state["scenario"]["request"] = {"operationId": scenario_op, "candidateStrategyVersionId": self.state["candidateStrategyVersionId"]}
            self.save()
        scenario = runs.get(scenario_op)
        if not scenario:
            self.admit(detail)
            self.state["scenario"]["attempted"] = True
            self.save()
            # Same operation ID recovers a lost response. It never creates a new generation identity.
            scenario = self.api("POST", self.endpoint("/scenario-evaluations"), self.state["scenario"]["request"])
            self.state["scenario"]["id"] = scenario["id"]
            self.save()
            return {"status": "SCENARIOS_STARTED_OR_RECOVERED", "operationId": scenario_op}
        if scenario["status"] == "PAUSED":
            raise Attention("SCENARIO_PAUSED_" + str(scenario.get("reason", "INSPECT_ORIGINAL_REQUEST")))
        if scenario["status"] != "COMPLETED":
            return self.waiting("SCENARIOS_IN_PROGRESS")
        scenario = self.completed_scenario(scenario_op)
        results = scenario.get("results", [])
        pairs = {row["caseId"] for row in results if all(any(other["caseId"] == row["caseId"] and other["variant"] == variant for other in results) for variant in ["BASELINE", "CANDIDATE"])}
        if scenario.get("totalCalls") != 40 or len(results) != 40 or len(pairs) != 20:
            raise Attention("SCENARIO_RESULTS_INCOMPLETE")
        failure_review = self.state["scenario"].get("failureReview", {})
        if any(row["status"] != "SUCCEEDED" for row in results) and failure_review.get("resultHash") != fingerprint(results):
            raise Attention("SCENARIO_FAILURES_REQUIRE_REVIEW_NO_AUTOMATIC_PAID_RETRY")
        evaluations = {item.get("operationId"): item for item in detail.get("evaluations", [])}
        allowed_ops = {item["operationId"] for item in self.state["evaluations"]}
        if any(key not in allowed_ops for key in evaluations):
            raise Attention("UNEXPECTED_EVALUATION_OPERATION")
        for operation in self.state["evaluations"]:
            existing = evaluations.get(operation["operationId"])
            if existing:
                if existing["status"] != "COMPLETED":
                    if detail["status"] == "PAUSED" or existing["status"] != "RUNNING":
                        raise Attention("EVALUATION_PAUSED_OR_FAILED")
                    return self.waiting("PAIRED_GAMES_IN_PROGRESS")
                if existing["validPairs"] != 1 or existing["completedGames"] != 2:
                    raise Attention("PAIRED_GAMES_NOT_BOTH_VALID")
                continue
            self.admit(detail)
            if "request" not in operation:
                profile = next((row for row in config["roster"] if row["personaId"] == operation["plan"]["personaId"]), None)
                if not profile:
                    raise Attention("EVALUATION_PERSONA_MISSING")
                operation["request"] = {"operationId": operation["operationId"], "participantId": profile["participantId"],
                                        "candidateStrategyVersionId": self.state["candidateStrategyVersionId"], "pairs": 1,
                                        "targetRole": operation["plan"]["targetRole"], "randomSeed": operation["plan"]["randomSeed"]}
            operation["attempted"] = True
            self.save()
            result = self.api("POST", self.endpoint("/evaluations"), operation["request"])
            operation["id"] = result["id"]
            self.save()
            return {"status": "PAIR_STARTED_OR_RECOVERED", "operationId": operation["operationId"], "targetRole": operation["plan"]["targetRole"]}
        self.state["executionCompleted"] = True
        self.state["settlementComplete"] = detail["cost"]["pendingCalls"] == 0
        self.save()
        return {"status": "PILOT_EXECUTION_COMPLETED" if self.state["settlementComplete"] else "PILOT_EXECUTION_COMPLETED_USAGE_PENDING",
                "settlementComplete": self.state["settlementComplete"], "trainingGames": len(training), "scenarioPairs": 20,
                "gamePairs": len(self.state["evaluations"]), "effect": "NOT_VERIFIED", "cost": detail["cost"]}

    def waiting(self, status):
        if time.time() - self.state.get("lastProgressEpoch", time.time()) >= 600:
            raise Attention("NO_OBSERVED_PROGRESS_600_SECONDS_ORIGINAL_WORK_PRESERVED")
        return {"status": status, "progress": self.state["progress"]}

    def acknowledge_scenario_failures(self, reviewer, notes):
        if not reviewer or not reviewer.strip() or not notes or not notes.strip():
            raise Attention("EXPLICIT_REVIEWER_AND_REVIEW_NOTES_REQUIRED")
        self.snapshot()
        operation_id = self.state["scenario"]["operationId"]
        scenario = self.completed_scenario(operation_id)
        results = scenario.get("results", [])
        if scenario.get("status") != "COMPLETED" or len(results) != 40 or not any(row["status"] == "FAILED" for row in results):
            raise Attention("REVIEW_REQUIRES_COMPLETED_SCENARIOS_WITH_RECORDED_FAILURES")
        self.state["scenario"]["failureReview"] = {"reviewedBy": reviewer.strip(), "notes": notes.strip(),
            "reviewedAt": now(), "operationId": operation_id, "resultHash": fingerprint(results),
            "decision": "CONTINUE_PLANNED_PAIRED_GAMES_WITH_FAILURES_PRESERVED"}
        self.save()
        return {"status": "SCENARIO_FAILURE_REVIEW_RECORDED", "operationId": operation_id,
                "failedAttempts": sum(row["status"] == "FAILED" for row in results), "paidCallsStarted": 0}

    def export(self):
        detail, matches = self.snapshot()
        strategies = self.api("GET", self.endpoint("/strategies"))
        calls, match_diagnostics = [], []
        for row in matches:
            diagnostics = self.api("GET", self.endpoint(f"/matches/{row['id']}/diagnostics"))
            match_diagnostics.append({"matchId": row["id"], **diagnostics})
            for call in diagnostics.get("calls", []):
                calls.append({"matchId": row["id"], **call})
        scenario_runs, scenario_calls = [], []
        for operation_id in detail["configuration"].get("scenarioRuns", {}):
            scenario = self.api("GET", self.endpoint("/scenario-evaluations/" + operation_id))
            scenario_runs.append(scenario)
            for call in scenario.get("calls", []):
                scenario_calls.append({"operationId": operation_id, "runId": scenario["id"], **call})
        ended = [row for row in matches if row.get("archiveId") and row["status"] in ["FINISHED", "REVIEWING", "REVIEW_FAILED", "REVIEWED"]]
        reviewed = [row for row in ended if row["status"] == "REVIEWED" and (not row.get("variant") or row["variant"] == "TRAINING")]
        completed_attempts = completed_scenario_pairs = legal_scenario_pairs = 0
        for scenario in scenario_runs:
            pairs = {}
            for row in scenario.get("results", []):
                if row.get("status") in ["SUCCEEDED", "FAILED"]:
                    completed_attempts += 1
                if row.get("caseId") and row.get("variant") in ["BASELINE", "CANDIDATE"]:
                    pairs.setdefault(row["caseId"], {}).setdefault(row["variant"], []).append(row.get("status"))
            for pair in pairs.values():
                baseline, candidate = pair.get("BASELINE", []), pair.get("CANDIDATE", [])
                if len(baseline) == len(candidate) == 1:
                    completed_scenario_pairs += all(status in ["SUCCEEDED", "FAILED"] for status in baseline + candidate)
                    legal_scenario_pairs += baseline == candidate == ["SUCCEEDED"]
        manifest = detail["configuration"].get("scenarioManifest", [])
        progress_summary = {
            "normallyEndedGames": len(ended), "successfulReviews": len(reviewed),
            "trainingClosedLoops": len(reviewed),
            "plannedTrainingGames": self.state["manifest"]["configuration"]["targetGames"],
            "scenarioCompletedAttempts": completed_attempts, "scenarioCompletedPairs": completed_scenario_pairs,
            "scenarioLegalSuccessPairs": legal_scenario_pairs, "scenarioPlannedPairs": len(manifest) if manifest else 20,
            "validGamePairs": sum(item.get("validPairs", (item.get("pairedOutcomes") or {}).get("sampleSize", 0)) for item in detail.get("evaluations", [])),
            "plannedGamePairs": len(self.state["evaluations"]),
            "sharedStrategyVersions": len(strategies["items"]),
            "candidateStrategyVersionId": self.state.get("candidateStrategyVersionId"),
        }
        unique_calls = {call["requestId"]: call for call in calls + scenario_calls}
        known_points = sum(call["billedPoints"] for call in unique_calls.values() if call.get("billedPoints") is not None)
        call_summary = {"uniqueRequests": len(unique_calls), "knownBilledPoints": known_points,
                        "unconfirmedRequests": sum(call.get("billedPoints") is None for call in unique_calls.values()),
                        "authoritativeSpentPoints": detail["cost"]["spentPoints"],
                        "authoritativePendingCalls": detail["cost"]["pendingCalls"],
                        "knownPointsDifference": detail["cost"]["spentPoints"] - known_points}
        result = {"exportedAt": now(), "run": self.state, "experiment": detail, "matches": matches,
                  "progressSummary": progress_summary, "strategies": strategies, "matchDiagnostics": match_diagnostics,
                  "matchCalls": calls, "scenarioRuns": scenario_runs,
                  "scenarioCalls": scenario_calls, "callSummary": call_summary,
                  "limitations": ["先导样本不证明稳定能力提升。", "保守区间假设各配对相互独立；同一来源的相关样本不能视为独立。", "情境合法性不等于语义或策略正确性。", "对局调用与独立情境调用分列，并按requestId去重汇总；待确认费用保留null，实扣总额以实验权威汇总为准。", "未执行角色发布，版本保持效果待验证。"]}
        destination = self.path.parent / (self.path.stem + "-results.json")
        write_json(destination, result)
        lines = ["# 狼人杀共享策略先导结果", "", f"实验：[{detail['id']}]({ORIGIN}/admin/lab/{detail['id']})", "",
                 f"状态：{detail['status']}；实扣 {detail['cost']['spentPoints']} / 3000 积分；待确认 {detail['cost']['pendingCalls']}。",
                 "执行完成与费用结算分开记录；仍有待确认费用时，不代表结算完成或免费。",
                 f"当前原因：{detail.get('reason') or '未提供'}。",
                 f"正常结束对局数（含评测）：{progress_summary['normallyEndedGames']}；成功复盘数：{progress_summary['successfulReviews']}；训练闭环数：{progress_summary['trainingClosedLoops']} / 计划 {progress_summary['plannedTrainingGames']}。",
                 "正常结束只统计有归档且处于 FINISHED / REVIEWING / REVIEW_FAILED / REVIEWED 的对局，排除 ABORTED；成功复盘与训练闭环只统计 REVIEWED 训练对局，评测不调用教练，不能计入复盘成功。",
                 "900 积分检查线可能提前结束训练；已结束但复盘失败的对局不会写成训练闭环。",
                 f"情境已完成尝试：{completed_attempts}；两边均已终态的尝试配对：{completed_scenario_pairs} / 计划 {progress_summary['scenarioPlannedPairs']}；两边合法成功配对：{legal_scenario_pairs}。",
                 "情境合法成功只表示两边状态均为 SUCCEEDED，不表示语义、事实或策略通过人工评审。",
                 f"完整对局有效配对数：{progress_summary['validGamePairs']} / 计划 {progress_summary['plannedGamePairs']}。",
                 "计划配置：DeepSeek LOW 玩家、GPT-6.1 Sol MAX 教练；实际流式链路与时间指标须由调用诊断另行核验。", "",
                 "效果待验证；该先导不用于宣称胜率或能力已提升。未向正式角色自动发布。", "",
                 "## 调用与结算", "",
                 f"对局调用记录 {len(calls)} 笔；独立情境调用记录 {len(scenario_calls)} 笔；按请求 ID 去重后共 {call_summary['uniqueRequests']} 笔。",
                 f"请求记录已确认实扣 {known_points} 积分；费用待确认记录 {call_summary['unconfirmedRequests']} 笔。",
                 f"实验权威汇总：实扣 {detail['cost']['spentPoints']} 积分，待确认 {detail['cost']['pendingCalls']} 笔；与请求已确认积分之差 {call_summary['knownPointsDifference']}。",
                 "两个调用集合均保留原请求 ID、结算状态与费用；缺少权威费用的值保持 null，不按零费用处理。", "",
                 "## 共享策略版本"]
        if not strategies["items"]:
            lines.extend(["", "没有共享策略可发布：尚未产生共享策略版本。"])
        lines.append("评测候选策略：" + (self.state.get("candidateStrategyVersionId") or "尚未冻结候选策略") + "。")
        versions_by_id = {version["id"]: version for version in strategies["items"]}
        for version in sorted(strategies["items"], key=lambda item: item["sequenceNumber"]):
            lines.extend(["", f"### v{version['sequenceNumber']} · {version['id']}", "",
                          "来源：" + json.dumps(version.get("evidence", {}), ensure_ascii=False), ""])
            for card in version["content"]["cards"]:
                lines.extend([f"- **{card['role']} / {card['id']}**：{card['condition']}", f"  - 建议：{card['advice']}",
                              f"  - 例外：{card['exceptions'] or '未列出'}", "  - 证据：" + json.dumps(card["evidence"], ensure_ascii=False)])
                if card.get("counterEvidence"):
                    lines.append("  - 反例证据：" + json.dumps(card["counterEvidence"], ensure_ascii=False))
                previous = versions_by_id.get(version.get("previousId"), {})
                before = next((item for item in previous.get("content", {}).get("cards", []) if item["id"] == card["id"]), None)
                if before and before.get("counterEvidence", []) != card.get("counterEvidence", []):
                    lines.append(f"  - 反例变化：前版 {len(before.get('counterEvidence', []))} 项 → 当前 {len(card.get('counterEvidence', []))} 项。")
        groups = {item["sourceGroup"] for item in manifest if item.get("sourceGroup")}
        lines.extend(["", "## 情境评测样本", "", f"冻结情境单元：{len(manifest)}；每个单元比较初始与候选策略，最多 {len(manifest)*2} 次调用。"])
        if groups:
            lines.append(f"构造来源：{len(groups)} 种；同来源下的人格变体不计为独立来源。")
        lines.extend(["", "## 成对整局结果", ""])
        for evaluation in detail.get("evaluations", []):
            lines.extend([f"### {evaluation['id']} · {evaluation.get('targetRole') or '随机身份'}", "",
                          f"状态：{evaluation['status']}；被测副本：{evaluation.get('participantId', '未提供')}。"])
            for variant, title in [("baseline", "初始策略"), ("candidate", "候选策略")]:
                stats = evaluation.get(variant, {})
                lines.append(f"- {title}：对局尝试 {stats.get('attemptedGames', 0)} / 未结束 {stats.get('unfinishedGames', 0)} / 失败 {stats.get('failedGames', 0)}。")
            paired = evaluation.get("pairedOutcomes")
            if paired is not None:
                lines.append(f"- 完整配对数：{paired['sampleSize']}；仅候选获胜 {paired['candidateOnlyWins']}；仅基线获胜 {paired['baselineOnlyWins']}；两侧结果相同 {paired['sameOutcome']}。")
                if paired.get("difference") is not None:
                    lines.append(f"- 成对胜率差异：{paired['difference']*100:.1f} 个百分点。")
                interval = paired.get("interval95")
                if interval:
                    lines.append(f"- 95% 保守区间（Hoeffding）：[{interval['lower']*100:.1f}, {interval['upper']*100:.1f}] 个百分点；方法 {interval['method']}。")
                else:
                    lines.append("- 暂无完整配对，未计算区间。")
            else:
                lines.append("- 本记录未提供成对胜负分布与区间。")
            lines.extend(["", "区间假设各配对相互独立；先导样本不足以证明能力提升。", ""])
        lines.extend(["", "## 评测与限制", ""] + result["limitations"])
        lines.extend(["", "完整配置、原操作 ID、对局诊断与失败状态、情境结果、成对整局结果、调用结算和证据见同名 JSON。"])
        destination.with_suffix(".md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return {"status": "EXPORTED", "json": str(destination), "markdown": str(destination.with_suffix('.md')),
                "executionCompleted": self.state.get("executionCompleted", False), "effect": "NOT_VERIFIED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["init", "create", "advance", "run", "poll", "export", "acknowledge-scenario-failures"])
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--api-helper", type=Path)
    parser.add_argument("--base-agent-ids", nargs=4)
    parser.add_argument("--evaluation-plan", type=Path)
    parser.add_argument("--source-commit")
    parser.add_argument("--seed", type=int, default=20261026)
    parser.add_argument("--max-seconds", type=int, default=50)
    parser.add_argument("--reviewed-by")
    parser.add_argument("--review-notes")
    args = parser.parse_args()
    args.state = args.state.resolve()
    pilot = None
    try:
        with exclusive_state(args.state):
            if args.action == "init":
                print(json.dumps(initialize(args), ensure_ascii=False))
                return 0
            if not args.api_helper:
                raise Attention("EXPLICIT_APPROVED_LOCAL_API_HELPER_REQUIRED")
            pilot = Pilot(args.state, Api(args.api_helper))
            if args.action == "create":
                result = pilot.create()
            elif args.action == "export":
                result = pilot.export()
            elif args.action == "acknowledge-scenario-failures":
                result = pilot.acknowledge_scenario_failures(args.reviewed_by, args.review_notes)
            elif args.action == "poll":
                pilot.snapshot()
                result = pilot.state["progress"]
            else:
                deadline = time.monotonic() + max(0, args.max_seconds)
                while True:
                    result = pilot.advance()
                    print(json.dumps(result, ensure_ascii=False), flush=True)
                    if args.action != "run" or result["status"] in EXECUTION_DONE or time.monotonic() + 10 > deadline:
                        return 0
                    time.sleep(10)
            print(json.dumps(result, ensure_ascii=False))
            return 0
    except Attention as error:
        if pilot:
            pilot.state["lastAttention"] = {"at": now(), "reason": str(error)}
            pilot.save()
        print(json.dumps({"status": "NEEDS_ATTENTION", "reason": str(error), "state": str(args.state), "originalRequestsPreserved": True}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
