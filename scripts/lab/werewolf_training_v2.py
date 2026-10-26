#!/usr/bin/env python3
"""Resumable V2 training acceptance. Authentication stays in the existing helper.

init is offline and saves operationId before any request. create first looks up
that operation; an unknown response never creates another identity. run starts
one representative case, then stops for inspection. acknowledge-representative
records the operator's review; resume continues the same run. No command
publishes strategies, changes models/budget, or starts a replacement run.

Example (all state/output files should live outside Git):
  python scripts/lab/werewolf_training_v2.py init --state <run.json> \
    --experiment-id <id> --source-commit <commit> \
    --external-diagnostics <JSON array of approved requestId/budgetId pairs>
  python scripts/lab/werewolf_training_v2.py create --state <run.json> --api-helper <helper.py>
  python scripts/lab/werewolf_training_v2.py run --state <run.json> --api-helper <helper.py>
  python scripts/lab/werewolf_training_v2.py acknowledge-representative --state <run.json> \
    --reviewed-by <operator> --review-notes <findings>
  python scripts/lab/werewolf_training_v2.py resume --state <run.json> --api-helper <helper.py>

poll/export are read-only API operations. Existing historical unknown charges
remain explicitly unknown and do not block independently authorized work. A
paused/blocked current task never triggers an automatic replacement or retry.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid

from werewolf_pilot import Api, Attention, exclusive_state, fingerprint, now, write_json

PREFIX = "/api/admin/lab/experiments"


def initialize(args):
    path = Path(args.state)
    if path.exists():
        raise Attention("STATE_ALREADY_EXISTS")
    request = {"operationId": str(uuid.uuid4()), "goal": "RULES_EVIDENCE"}
    if args.external_diagnostics:
        rows = json.loads(Path(args.external_diagnostics).read_text(encoding="utf-8-sig"))
        if not isinstance(rows, list) or any(not isinstance(row, dict) or set(row) != {"requestId", "budgetId"}
                                            or not all(isinstance(value, str) and value for value in row.values()) for row in rows):
            raise Attention("EXTERNAL_DIAGNOSTICS_MUST_LIST_REQUEST_AND_BUDGET_IDS")
        request["externalDiagnostics"] = rows
    state = {"schemaVersion": 2, "createdAt": now(), "experimentId": args.experiment_id,
             "sourceCommit": args.source_commit, "request": request, "requestHash": fingerprint(request),
             "events": [], "status": "INITIALIZED"}
    write_json(path, state)
    return state


def validate_state(state):
    if state.get("schemaVersion") != 2 or fingerprint(state.get("request")) != state.get("requestHash"):
        raise Attention("SAVED_REQUEST_CHANGED")
    if state["request"].get("goal") != "RULES_EVIDENCE" or not state.get("experimentId"):
        raise Attention("INVALID_TRAINING_IDENTITY")


def route(state):
    from urllib.parse import quote
    return f'{PREFIX}/{quote(state["experimentId"], safe="")}/training-runs/{quote(state["request"]["operationId"], safe="")}'


def record(state, event, **fields):
    state["events"].append({"at": now(), "event": event, **fields})


def capture(state, value):
    if value.get("operationId") != state["request"]["operationId"] or value.get("experimentId") != state["experimentId"]:
        raise Attention("SERVER_RUN_IDENTITY_MISMATCH")
    state["run"] = value
    state["lastObservedAt"] = now()
    state["status"] = value["status"]
    state["executionCompleted"] = value["status"] == "COMPLETED"
    state["usagePending"] = value.get("cost", {}).get("pendingCalls", 0) > 0
    state["settlementComplete"] = state["executionCompleted"] and not state["usagePending"]
    return value


def poll(state, api):
    validate_state(state)
    value = capture(state, api("GET", route(state)))
    pending = state.get("pendingControl")
    if pending:
        # Resolve by the returned current status. Same-state observations are not
        # proof a timed-out command was rejected, so never replay them blindly.
        action = pending["action"]
        resolved = ((action in {"start-one", "resume"} and value["status"] in {"RUNNING", "COMPLETED"})
                    or (action == "start-one" and value.get("reason") == "REPRESENTATIVE_CASE_READY")
                    or (action == "pause" and value["status"] == "PAUSED")
                    or (action == "stop" and value["status"] == "STOPPED"))
        if resolved:
            record(state, "CONTROL_RECOVERED", action=action)
            del state["pendingControl"]
    return value


def create(state, api, save):
    validate_state(state)
    try:
        return poll(state, api)
    except Attention as error:
        if str(error) != "API_HTTP_404":
            raise
    state["createAttemptedAt"] = now()
    save(state)
    try:
        value = api("POST", route(state).rsplit("/", 1)[0], state["request"])
        capture(state, value)
        record(state, "CREATED_OR_RECOVERED")
        save(state)
        return value
    except Attention:
        record(state, "CREATE_RESPONSE_UNKNOWN", operationId=state["request"]["operationId"])
        save(state)
        raise


def admit(run, new_work=True):
    cost = run["cost"]
    if cost["totalKnownPoints"] >= cost["budgetPoints"]:
        raise Attention("ACTUAL_COST_STOP_LINE_REACHED")
    if "BUDGET" in (run.get("reason") or ""):
        raise Attention(run["reason"])
    # Historical pending fees are not a new global gate. Current unresolved
    # actions still require investigation and are never sent again here.
    if any(task["status"] in ({"WAITING", "BLOCKED"} if new_work else {"BLOCKED"}) for task in run.get("tasks", [])):
        raise Attention("CURRENT_TASK_REQUIRES_ATTENTION")


def representative_fingerprint(run):
    tasks = [task for task in run.get("tasks", []) if task["kind"] in {"PRACTICE", "TEACH"} and task["status"] == "SUCCEEDED"]
    if not any(task["kind"] == "PRACTICE" for task in tasks) or not any(task["kind"] == "TEACH" for task in tasks):
        raise Attention("REPRESENTATIVE_RESULTS_INCOMPLETE")
    return fingerprint([{key: task.get(key) for key in ("id", "caseId", "kind", "result", "requestId")} for task in tasks])


def acknowledge(state, reviewed_by, review_notes):
    value = state.get("run", {})
    if value.get("status") != "PAUSED" or value.get("reason") != "REPRESENTATIVE_CASE_READY":
        raise Attention("NOT_AT_REPRESENTATIVE_CHECKPOINT")
    if not reviewed_by.strip() or not review_notes.strip():
        raise Attention("REVIEWER_AND_FINDINGS_REQUIRED")
    state["representativeReview"] = {"reviewedBy": reviewed_by, "notes": review_notes,
                                     "reviewedAt": now(), "resultHash": representative_fingerprint(value)}
    record(state, "REPRESENTATIVE_REVIEW_RECORDED")


def control(state, api, action, save):
    value = poll(state, api)
    save(state)
    if state.get("pendingControl"):
        raise Attention("CONTROL_OUTCOME_UNCONFIRMED_QUERY_ORIGINAL_RUN")
    if action in {"start-one", "resume"}:
        admit(value)
        if action == "start-one" and value["status"] != "DRAFT":
            raise Attention("RUN_ALREADY_STARTED")
        if action == "resume":
            if value["status"] != "PAUSED":
                raise Attention("RUN_NOT_PAUSED")
            if value.get("reason") == "REPRESENTATIVE_CASE_READY":
                if state.get("representativeReview", {}).get("resultHash") != representative_fingerprint(value):
                    raise Attention("REVIEW_REPRESENTATIVE_BEFORE_CONTINUING")
            elif any(task["status"] in {"FAILED", "BLOCKED", "WAITING"} for task in value.get("tasks", [])):
                raise Attention("CURRENT_FAILURE_REQUIRES_REVIEW_NO_AUTOMATIC_RETRY")
    state["pendingControl"] = {"action": action, "at": now()}
    save(state)
    try:
        result = capture(state, api("POST", route(state) + "/control/" + action))
        del state["pendingControl"]
        record(state, "CONTROL_CONFIRMED", action=action)
        save(state)
        return result
    except Attention:
        record(state, "CONTROL_RESPONSE_UNKNOWN", action=action)
        save(state)
        raise


def advance(state, api, save):
    value = poll(state, api)
    save(state)
    if state.get("pendingControl"):
        raise Attention("CONTROL_OUTCOME_UNCONFIRMED_QUERY_ORIGINAL_RUN")
    if value["status"] == "DRAFT":
        return control(state, api, "start-one", save)
    if value["status"] == "PAUSED":
        raise Attention(value.get("reason") or "TRAINING_PAUSED")
    if value["status"] == "STOPPED":
        raise Attention("TRAINING_STOPPED")
    if value["status"] == "RUNNING":
        admit(value, new_work=False)
    return value


def export(state, api, destination):
    value = poll(state, api)
    cases = api("GET", route(state) + "/cases")
    detail = api("GET", f'{PREFIX}/{state["experimentId"]}')
    strategies = api("GET", f'{PREFIX}/{state["experimentId"]}/strategies')
    matches, match_diagnostics, page = [], [], 0
    while True:
        batch = api("GET", f'{PREFIX}/{state["experimentId"]}/matches?page={page}&size=20')
        matches.extend(batch["items"])
        if len(matches) >= batch["total"] or not batch["items"]: break
        page += 1
    for match in matches:
        match_diagnostics.append(api("GET", f'{PREFIX}/{state["experimentId"]}/matches/{match["id"]}/diagnostics'))
    report = {"exportedAt": now(), "state": state, "run": value, "cases": cases, "experiment": detail,
              "matches": matches, "matchDiagnostics": match_diagnostics,
              "strategies": strategies, "executionCompleted": value["status"] == "COMPLETED",
              "usagePending": value["cost"]["pendingCalls"] > 0}
    path = Path(destination)
    path.mkdir(parents=True, exist_ok=True)
    write_json(path / "training-v2.json", report)
    (path / "training-v2.md").write_text(markdown(report), encoding="utf-8")
    return report


def markdown(report):
    value, state = report["run"], report["state"]
    cost, progress = value["cost"], value["progress"]
    lines = ["# 狼人杀训练 V2 实跑记录", "", f'- 实验：{value["experimentId"]}',
             f'- 运行：{value["id"]} / 操作：{value["operationId"]}',
             f'- 源码版本：{state["sourceCommit"]}', f'- 状态：{value["status"]} / 阶段：{value["stage"]}',
             f'- 原因：{value.get("reason") or "无"}', f'- 下一步：{value.get("nextStep") or "未提供"}', "",
             f'- 实验累计实扣：{cost["spentPoints"]} 积分（包含历史实扣）',
             f'- 外部诊断计价：{cost["externalPoints"]} 积分（独立登记，不重复扣款）',
             f'- 已知合计：{cost["totalKnownPoints"]} / 停止线 {cost["budgetPoints"]} 积分',
             f'- 待确认费用：{cost["pendingCalls"]} 笔；未知不等于免费，不能宣称结算完成。' if cost["pendingCalls"] else '- 待确认费用：0 笔', "",
             f'- 开发练习案例：{progress["developmentCases"]}；任务完成 {progress["completedTasks"]}/{progress["totalTasks"]}，失败 {progress["failedTasks"]}',
             f'- 独立保留题完成配对：{progress["completedHoldoutPairs"]}/{progress["holdoutPairs"]}',
             f'- 完整对局完成配对：{progress["completedGamePairs"]}/{progress["gamePairs"]}',
             '- 练习、重复人格和同源题不算独立评测样本；少量整局不足以证明胜率提升。', "",
             f'- 本次候选策略：{value.get("candidateStrategyVersionId") or "无；没有本次共享策略可发布"}',
             f'- 评测结论：{value.get("assessment", {}).get("status", "NOT_RUN")}；缺评分保持 UNSCORED。',
             '- 本脚本不发布到正式角色。', "", "## 任务与证据", ""]
    for task in value.get("tasks", []):
        lines.extend([f'### {task["kind"]} · {task["caseId"]} · {task["status"]}', "",
                      f'- 任务：{task["id"]}；请求：{task.get("requestId") or "未发送"}',
                      f'- 原因：{task.get("reason") or "无"}；尝试 {task.get("attempt", 0)}；付费重试 {task.get("paidRetries", 0)}'])
        for call in task.get("diagnostics", {}).get("calls", []):
            timing = call.get("timing") or {}
            first = f'{timing["firstTextMs"] / 1000:.3f} 秒' if isinstance(timing.get("firstTextMs"), (int, float)) else "未记录"
            total = f'{timing["totalMs"] / 1000:.3f} 秒' if isinstance(timing.get("totalMs"), (int, float)) else "未记录"
            points = call.get("billedPoints")
            lines.append(f'- 调用 {call["requestId"]}：{call.get("state", "UNKNOWN")}；实扣 {str(points) + " 积分" if points is not None else "待确认"}；正文 TTFT {first}；总耗时 {total}')
            lines.extend(["", "```json", json.dumps({"usage": call.get("usage"), "timing": timing}, ensure_ascii=False, indent=2), "```"])
        lines.extend(["", "```json", json.dumps({"result": task.get("result"), "failure": task.get("diagnostics", {}).get("failure")}, ensure_ascii=False, indent=2), "```", ""])
    lines.extend(["## 完整评测结论", "", "```json", json.dumps(value.get("assessment"), ensure_ascii=False, indent=2), "```", ""])
    candidate = next((version for version in report["strategies"].get("items", []) if version["id"] == value.get("candidateStrategyVersionId")), None)
    if candidate:
        lines.extend(["## 本次候选策略", "", "```json", json.dumps(candidate, ensure_ascii=False, indent=2), "```", ""])
    lines.extend(["## 历史对局及请求记录", "", "历史对局和旧复盘不计入本次课程完成数；其费用已包含在实验累计实扣中。", ""])
    for diagnostics in report.get("matchDiagnostics", []):
        lines.extend(["```json", json.dumps(diagnostics, ensure_ascii=False, indent=2), "```", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["init", "create", "poll", "run", "resume", "pause", "stop", "acknowledge-representative", "export"])
    parser.add_argument("--state", required=True)
    parser.add_argument("--api-helper")
    parser.add_argument("--experiment-id")
    parser.add_argument("--source-commit")
    parser.add_argument("--external-diagnostics")
    parser.add_argument("--reviewed-by", default="")
    parser.add_argument("--review-notes", default="")
    parser.add_argument("--output-dir")
    parser.add_argument("--seconds", type=int, default=50)
    args = parser.parse_args()
    if args.command == "init" and (not args.experiment_id or not args.source_commit):
        parser.error("init requires --experiment-id and --source-commit")
    if args.command not in {"init", "acknowledge-representative"} and not args.api_helper:
        parser.error("API commands require the existing --api-helper")
    if args.command == "export" and not args.output_dir:
        parser.error("export requires --output-dir")
    if not 1 <= args.seconds <= 60:
        parser.error("--seconds must be 1..60; reinvoke bounded run to continue")
    with exclusive_state(args.state):
        state = None
        try:
            if args.command == "init":
                state = initialize(args)
            else:
                state = json.loads(Path(args.state).read_text(encoding="utf-8-sig"))
                validate_state(state)
                save = lambda value: write_json(args.state, value)
                if args.command == "acknowledge-representative":
                    acknowledge(state, args.reviewed_by, args.review_notes)
                else:
                    api = Api(args.api_helper)
                    if args.command == "create": create(state, api, save)
                    elif args.command == "poll": poll(state, api)
                    elif args.command in {"resume", "pause", "stop"}: control(state, api, args.command, save)
                    elif args.command == "export": export(state, api, args.output_dir)
                    elif args.command == "run":
                        deadline = time.monotonic() + args.seconds
                        while True:
                            value = advance(state, api, save)
                            if value["status"] == "COMPLETED" or time.monotonic() >= deadline: break
                            time.sleep(min(5, max(0, deadline-time.monotonic())))
                save(state)
            print(json.dumps({"status": state["status"], "operationId": state["request"]["operationId"],
                              "stage": state.get("run", {}).get("stage"), "cost": state.get("run", {}).get("cost"),
                              "usagePending": state.get("usagePending")}, ensure_ascii=False))
            return 0
        except Attention as error:
            if state:
                state["attention"] = {"at": now(), "reason": str(error)}
                write_json(args.state, state)
            print(str(error), file=sys.stderr)
            return 2


if __name__ == "__main__":
    sys.exit(main())
