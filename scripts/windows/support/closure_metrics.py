"""Strict evidence checks, shared by pilot and final reports. No model, database or inferred scores."""
import argparse
import hashlib
import json
import math
import re
from collections import Counter

DIMENSIONS = ("naturalness", "responseSpecificity", "personaContinuity", "emotionActionConsistency")
GAMES = ("undercover", "werewolf", "turtle_soup")
META = ("id", "group", "gameId", "personaId", "scenarioId", "sequenceId", "stepId")
HEADER = ("evaluationSchemaVersion", "evaluationSetVersion", "batchId", "sourceFingerprint", "buildId", "promptVersion", "inputFormatVersion", "memoryFormatVersion", "evidenceKind")
STEPS = ("INITIAL", "COUNTEREVIDENCE", "ACTION", "CONTINUITY")
REASONS = {"CONTENT_REVIEW_FAILED", "ADMIN_CONTROL", "MODEL_ACTION_APPLIED", "FALLBACK_APPLIED", "INSTANCE_UNAVAILABLE", "TURN_OBSOLETE", "TURN_EXPIRED", "RECOVERY_TIMEOUT", "DISPATCH_REJECTED", "NO_ACTION_BUDGET", "SUBMISSION_EXCEPTION", "EXECUTION_EXCEPTION", "INVALID_FALLBACK"}

def number(value, minimum=0, maximum=math.inf):
    try:
        return type(value) in (int, float) and math.isfinite(value) and minimum <= value <= maximum
    except OverflowError:
        return False

def nonblank(value):
    return isinstance(value, str) and bool(value.strip())

def keyed(items, label):
    if not isinstance(items, list) or any(not isinstance(r, dict) or not nonblank(r.get("id")) for r in items):
        raise ValueError(label + "_INVALID_LIST")
    result = {r["id"]: r for r in items}
    if len(result) != len(items):
        raise ValueError(label + "_DUPLICATE_ID")
    return result

def same_header(first, *others, expected_set="closure-v2"):
    valid = first.get("evaluationSchemaVersion") == 2 and type(first.get("evaluationSchemaVersion")) is int and first.get("evaluationSetVersion") == expected_set
    valid &= all(nonblank(first.get(k)) for k in ("batchId", "sourceFingerprint", "buildId", "promptVersion"))
    valid &= all(type(first.get(k)) is int and first[k] > 0 for k in ("inputFormatVersion", "memoryFormatVersion"))
    valid &= all(isinstance(first.get(k),str) and bool(re.fullmatch("[a-f0-9]{64}",first[k])) for k in ("sourceFingerprint","buildId"))
    valid &= first.get("evidenceKind") in ("REAL_MODEL", "SYNTHETIC_TEST")
    return valid and all(all(type(other.get(k)) is type(first.get(k)) and other.get(k) == first.get(k) for k in HEADER) for other in others)

def reference_valid(ref, rows, sample_id=None):
    if not isinstance(ref, dict) or ref.get("sampleId") not in rows or (sample_id and ref["sampleId"] != sample_id):
        return False
    row = rows[ref["sampleId"]]
    if set(ref) == {"sampleId", "eventId"}:
        return nonblank(ref["eventId"]) and any(e.get("eventId") == ref["eventId"] for e in row.get("observation", {}).get("events", []) if isinstance(e, dict))
    if set(ref) != {"sampleId", "pointer"} or not isinstance(ref.get("pointer"), str) or not ref["pointer"].startswith("/decision/"):
        return False
    value = row
    try:
        for key in ref["pointer"].split("/")[1:]:
            key = key.replace("~1", "/").replace("~0", "~")
            value = value[int(key)] if isinstance(value, list) else value[key]
        return nonblank(value) or type(value) is bool or number(value)
    except (ValueError, KeyError, IndexError, TypeError):
        return False

def references_valid(value, rows, sample_id=None):
    return isinstance(value, list) and bool(value) and all(reference_valid(ref, rows, sample_id) for ref in value)

def base_checks(manifest, evidence, review, ids=None, expected_set="closure-v2"):
    planned = keyed(manifest.get("samples"), "MANIFEST")
    rows = keyed(evidence.get("samples", []), "EVIDENCE")
    reviews = keyed(review.get("samples", []), "REVIEW")
    wanted = set(planned) if ids is None else set(ids)
    problems = []
    if not same_header(manifest, evidence, review, expected_set=expected_set): problems.append("VERSION_OR_BATCH_MISMATCH")
    if set(rows) != wanted or set(reviews) != wanted: problems.append("INCOMPLETE_OR_UNKNOWN_COVERAGE")
    for key in wanted:
        item = planned.get(key, {}); row = rows.get(key, {}); judgment = reviews.get(key, {})
        if any(k not in row or row[k] != item.get(k) for k in META): problems.append(key + ":METADATA_MISMATCH")
        if item.get("gameId") not in GAMES or item.get("group") not in ("SINGLE", "SEQUENCE", "HOST"): problems.append(key + ":INVALID_MANIFEST_GROUP")
        if item.get("personaId") not in (("HOST",) if item.get("group") == "HOST" else ("ai1", "ai2", "ai3", "ai4")): problems.append(key + ":INVALID_PERSONA")
        decision = row.get("decision", {}); fallback = decision.get("fallback") if isinstance(decision, dict) else None
        if type(fallback) is not bool: problems.append(key + ":FALLBACK_UNKNOWN")
        expected = "LOCAL_FALLBACK" if fallback is True else "GENERATED" if fallback is False else None
        if expected is None or row.get("status") != expected: problems.append(key + ":STATUS_FALLBACK_CONFLICT")
        if judgment.get("visibleInformation") != "NO_OBSERVED_VIOLATION": problems.append(key + ":BOUNDARY_NOT_PASSED")
        if item.get("group") == "HOST":
            if judgment.get("hostCorrect") is not True or not references_valid(judgment.get("hostEvidence"), rows, key): problems.append(key + ":HOST_NOT_PASSED")
        else:
            for dimension in DIMENSIONS:
                if not number(judgment.get("scores", {}).get(dimension), 1, 5) or not references_valid(judgment.get("evidence", {}).get(dimension), rows, key):
                    problems.append(key + ":" + dimension + ":MISSING_SCORE_OR_EVIDENCE")
    return planned, rows, reviews, problems

def pilot(manifest, evidence, review):
    ids = manifest.get("pilotIds", [])
    planned, rows, reviews, problems = base_checks(manifest, evidence, review, ids)
    if len(ids) != 12 or len(set(ids)) != 12 or any(planned.get(i, {}).get("group") != "SINGLE" for i in ids): problems.append("INVALID_PILOT_INVENTORY")
    if {(planned.get(i, {}).get("gameId"), planned.get(i, {}).get("personaId")) for i in ids} != {(g, p) for g in GAMES for p in ("ai1", "ai2", "ai3", "ai4")}: problems.append("INVALID_PILOT_MATRIX")
    if review.get("status") != "PASS": problems.append("PILOT_REVIEW_NOT_PASSED")
    if sum(r.get("decision", {}).get("fallback") is True for r in rows.values()) > 1: problems.append("PILOT_FALLBACK_GATE")
    for r in reviews.values():
        if any(not number(r.get("scores", {}).get(d), 4, 5) for d in DIMENSIONS): problems.append("PILOT_SCORE_GATE")
    return {"algorithmPassed": not problems, "pilotPassed": not problems and manifest.get("evidenceKind") == "REAL_MODEL", "problems": sorted(set(problems))}

def measure(manifest, evidence, review, games=None, runtime=None, games_sha256=None, evidence_sha256=None):
    planned, rows, reviews, problems = base_checks(manifest, evidence, review)
    if not nonblank(evidence_sha256) or review.get("evidenceSha256") != evidence_sha256: problems.append("DECISION_EVIDENCE_HASH_MISMATCH")
    groups = {}
    for group in ("SINGLE", "SEQUENCE", "HOST"):
        ids = [k for k, p in planned.items() if p["group"] == group]
        reported = [k for k in ids if rows.get(k, {}).get("status") in ("GENERATED", "LOCAL_FALLBACK")]
        known = [k for k in reported if type(rows[k].get("decision", {}).get("fallback")) is bool]
        completed = [k for k in known if rows[k]["status"] == ("LOCAL_FALLBACK" if rows[k]["decision"]["fallback"] else "GENERATED")]
        fallback = sum(rows[k]["decision"]["fallback"] for k in known)
        group_result = dict(planned=len(ids), completed=len(completed), fallbackKnown=len(known), fallbackUnknown=len(ids)-len(known), fallbackCount=fallback,
                            fallbackRatio=fallback/len(known) if known else None, means={})
        for persona in (() if group == "HOST" else ("ALL", "ai1", "ai2", "ai3", "ai4")):
            subset = [k for k in ids if persona == "ALL" or planned[k]["personaId"] == persona]
            dimensions = {}
            for dimension in DIMENSIONS:
                values = [reviews[k]["scores"][dimension] for k in subset if k in reviews and number(reviews[k].get("scores", {}).get(dimension), 1, 5)
                          and references_valid(reviews[k].get("evidence", {}).get(dimension), rows, k)]
                dimensions[dimension] = dict(mean=sum(values)/len(values) if values else None, n=len(values))
                if not values or len(values) != len(subset) or sum(values)/len(values) < 4: problems.append(f"{group}:{persona}:{dimension}:QUALITY_GATE")
            group_result["means"][persona] = dimensions
        groups[group] = group_result
    if {g: groups[g]["planned"] for g in groups} != {"SINGLE":120, "SEQUENCE":48, "HOST":12}: problems.append("INVALID_FROZEN_SAMPLE_COUNTS")
    for game in GAMES:
        for behavior in ("questioning", "siding", "revision", "commitment"):
            item = review.get("personaComparisons", {}).get(game, {}).get(behavior, {})
            refs = item.get("references", [])
            selected = [planned.get(r.get("sampleId"), {}) for r in refs if isinstance(r, dict)] if isinstance(refs, list) else []
            cohorts = {(p.get("group"), p.get("scenarioId"), p.get("sequenceId"), p.get("stepId")) for p in selected}
            if (item.get("verdict") != "PASS" or not nonblank(item.get("assessment")) or not references_valid(refs, rows)
                or len({p.get("personaId") for p in selected}) < 2 or len(cohorts) != 1
                or any(p.get("gameId") != game or p.get("group") == "HOST" for p in selected)):
                problems.append(f"{game}:{behavior}:PERSONA_DIFFERENCE_NOT_PASSED")
    problems.extend(sequence_checks(planned, rows))
    known = sum(groups[g]["fallbackKnown"] for g in ("SINGLE", "SEQUENCE")); fallback = sum(groups[g]["fallbackCount"] for g in ("SINGLE", "SEQUENCE"))
    ratio = fallback/known if known else None
    if known != 168 or ratio is None or ratio > .1: problems.append("FALLBACK_GATE")
    final = runtime_checks(manifest, games, runtime, games_sha256)
    attempts = [a for r in rows.values() for a in r.get("decision", {}).get("diagnostics", {}).get("attempts", [])]
    measurements = {}
    for field in ("rpcMs", "inputBytes", "outputBytes", "promptTokens", "completionTokens"):
        values = [a[field] for a in attempts if number(a.get(field))]
        if any(field in a and a[field] is not None and not number(a[field]) for a in attempts): problems.append(field+":INVALID_MEASUREMENT")
        measurements[field] = dict(n=len(values), missing=len(attempts)-len(values), mean=sum(values)/len(values) if values else None)
    synthetic = manifest.get("evidenceKind") != "REAL_MODEL"
    return dict(algorithmPassed=not problems, evaluationPassed=not problems and not synthetic, L4Passed=not problems and not final and not synthetic,
                synthetic=synthetic, finalAlgorithmPassed=not problems and not final, problems=sorted(set(problems)), finalGateProblems=sorted(set(final)), groups=groups,
                multiplayerPersonaFallbackRatio=ratio, measurements=measurements, reviewType="CODING_AGENT_NOT_INDEPENDENT_HUMAN_BLIND_REVIEW")

def sequence_checks(planned, rows):
    problems = []
    sequences = {}
    for key, p in planned.items():
        if p["group"] == "SEQUENCE": sequences.setdefault((p["gameId"],p["personaId"],p["sequenceId"]), []).append(key)
    for cohort, ids in sequences.items():
        if {planned[k]["stepId"] for k in ids} != set(STEPS) or len(ids) != 4: problems.append(str(cohort)+":INVALID_SEQUENCE_STEPS")
        for key in ids:
            row = rows.get(key, {}); cover = row.get("coverage", {})
            obs = row.get("observation", {})
            phases = {"undercover":("DESCRIPTION","RESPONSE","VOTING","DESCRIPTION"),"werewolf":("DAY_DISCUSS","DAY_INTERACTION","DAY_VOTE","DAY_DISCUSS"),"turtle_soup":("QUESTIONING",)*4}
            stage = planned[key]["stepId"]
            if stage not in STEPS or obs.get("phase") != phases[planned[key]["gameId"]][STEPS.index(stage)]: problems.append(key+":UNEXPECTED_SEQUENCE_PHASE")
            visible = {e.get("eventId") for e in obs.get("events", [])}
            if (cover.get("status") != "APPLIED" or cover.get("stepId") != planned[key]["stepId"] or cover.get("phase") != obs.get("phase")
                or type(cover.get("round")) is not int or cover.get("round") != obs.get("round")
                or not isinstance(cover.get("producedEventIds"),list) or (not cover.get("producedEventIds") and not cover.get("recordedDecision"))
                or cover.get("action") != row.get("decision", {}).get("action")
                or cover.get("origin") != ("LEGAL_FALLBACK" if row.get("decision",{}).get("fallback") is True else "MODEL")):
                problems.append(key+":SEQUENCE_NOT_APPLIED")
            if planned[key]["stepId"] != "INITIAL" and (not cover.get("requiredVisibleEventIds") or not set(cover.get("requiredVisibleEventIds", [])) <= visible):
                problems.append(key+":SEQUENCE_EVIDENCE_MISSING")
        first = rows.get(next((k for k in ids if planned[k]["stepId"]=="INITIAL"), ""), {})
        last = rows.get(next((k for k in ids if planned[k]["stepId"]=="CONTINUITY"), ""), {})
        field = "cycle" if cohort[0]=="turtle_soup" else "round"
        start,end = first.get("coverage",{}).get(field),last.get("coverage",{}).get(field)
        if type(start) is not int or type(end) is not int or end <= start: problems.append(str(cohort)+":SEQUENCE_NOT_ADVANCED")
        action_ids = set(rows.get(next((k for k in ids if planned[k]["stepId"]=="ACTION"), ""), {}).get("coverage", {}).get("producedEventIds", []))
        fourth = rows.get(next((k for k in ids if planned[k]["stepId"]=="CONTINUITY"), ""), {})
        action_row = rows.get(next((k for k in ids if planned[k]["stepId"]=="ACTION"), ""), {})
        record = action_row.get("coverage", {}).get("recordedDecision")
        recorded = record and record in fourth.get("observation", {}).get("memory", {}).get("recentDecisions", [])
        if not action_ids.intersection(e.get("eventId") for e in fourth.get("observation", {}).get("events", [])) and not recorded: problems.append(str(cohort)+":ACTION_NOT_IN_CONTINUITY")
    return problems


def runtime_checks(manifest, games, runtime, games_sha256):
    errors = []
    if games is None or runtime is None: return ["MISSING_CURRENT_GAMES_OR_RUNTIME_REPORT"]
    if not same_header(manifest, games, runtime): errors.append("RUNTIME_VERSION_OR_BATCH_MISMATCH")
    if not nonblank(games_sha256) or runtime.get("evidenceSha256") != games_sha256: errors.append("GAME_EVIDENCE_HASH_MISMATCH")
    scenarios = games.get("scenarios", [])
    expected = {(g,h) for g in GAMES for h in (1,3)}
    if len(scenarios) != 6 or {(s.get("gameId"),s.get("humanCount")) for s in scenarios} != expected: errors.append("SIX_GAME_COVERAGE")
    scope = {(s.get("roomId"),s.get("archiveId")) for s in scenarios}
    if len(scope) != 6 or any(not nonblank(r) or not nonblank(a) for r,a in scope) or len({a for _,a in scope}) != 6: errors.append("INVALID_GAME_IDENTITIES")
    if runtime.get("instances") != [dict(roomId=r,archiveId=a) for r,a in sorted(scope, key=str)]: errors.append("RUNTIME_SCOPE_MISMATCH")
    for s in scenarios:
        if type(s.get("humanCount")) is not int: errors.append("INVALID_HUMAN_COUNT")
        if s.get("status") != "passed" or s.get("resumedFrom") or s.get("privacyViolations") != []: errors.append("GAME_NOT_PASSED_FRESH")
        if s.get("gameId") == "turtle_soup" and (s.get("winner") != "SOLVED" or s.get("contractChecks", {}).get("successfulScriptedSolve") is not True): errors.append("SOUP_NOT_SOLVED")
    jobs = keyed(runtime.get("taskLinks", []), "TASKS"); traces = keyed(runtime.get("traceLinks", []), "TRACES")
    linked = Counter(t.get("jobId") for t in traces.values())
    terminal = succeeded = fallback = 0
    for job in jobs.values():
        if (job.get("roomId"),job.get("instanceId")) not in scope or job.get("buildId") != manifest.get("buildId") or job.get("sourceFingerprint") != manifest.get("sourceFingerprint") or job.get("promptVersion") != manifest.get("promptVersion"): errors.append("JOB_IDENTITY_MISMATCH")
        if job.get("status") not in ("SUCCEEDED","FAILED","DISCARDED"): errors.append("PENDING_OR_UNKNOWN_TASK")
        else:
            terminal += 1
            if job.get("terminalReason") not in REASONS or job.get("terminalStatus") != job["status"]: errors.append("TERMINAL_REASON_COVERAGE")
        if job.get("status") == "SUCCEEDED":
            succeeded += 1
            if linked[job["id"]] != 1: errors.append("SUCCESS_TRACE_LINK_COVERAGE")
    for trace in traces.values():
        job = jobs.get(trace.get("jobId"), {})
        if not job or job.get("status") != "SUCCEEDED" or any(trace.get(k) != job.get(k) for k in ("instanceId","roomId","buildId","sourceFingerprint","promptVersion")): errors.append("ORPHAN_OR_MISMATCHED_TRACE")
        if type(trace.get("fallback")) is not bool: errors.append("LIVE_FALLBACK_UNKNOWN")
        else: fallback += trace["fallback"]
    if not succeeded or not terminal: errors.append("EMPTY_RUNTIME_DENOMINATOR")
    if not traces or fallback/len(traces) > .1: errors.append("LIVE_FALLBACK_GATE")
    if {j.get("instanceId") for j in jobs.values()} != {a for _,a in scope}: errors.append("MISSING_INSTANCE_TASKS")
    # Aggregates are checked against the links instead of being trusted as the denominator.
    expected_counts = Counter(j.get("status") for j in jobs.values())
    reported = runtime.get("jobs", {}).get("statusCounts", {})
    if any(type(v) is not int or v < 0 for v in reported.values()) or any(reported.get(k,0) != v for k,v in expected_counts.items()) or any(v != expected_counts[k] for k,v in reported.items()): errors.append("RUNTIME_COUNT_MISMATCH")
    if runtime.get("flags"): errors.append("UNRESOLVED_RUNTIME_FLAGS")
    return errors

def load(path):
    with open(path, encoding="utf-8-sig") as stream: return json.load(stream)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("manifest", "evidence", "review"): parser.add_argument("--"+name, required=True)
    parser.add_argument("--games"); parser.add_argument("--runtime"); parser.add_argument("--mode", choices=("final","pilot"), default="final")
    args = parser.parse_args()
    try:
        m,e,r = load(args.manifest),load(args.evidence),load(args.review)
        if args.mode == "pilot":
            result = pilot(m,e,r)
            if r.get("evidenceSha256") != hashlib.sha256(open(args.evidence,"rb").read()).hexdigest():
                result["pilotPassed"] = result["algorithmPassed"] = False; result["problems"].append("PILOT_EVIDENCE_HASH_MISMATCH")
        else: result = measure(m,e,r,load(args.games) if args.games else None,load(args.runtime) if args.runtime else None,hashlib.sha256(open(args.games,"rb").read()).hexdigest() if args.games else None,hashlib.sha256(open(args.evidence,"rb").read()).hexdigest())
        print(json.dumps(result, ensure_ascii=True, indent=2, allow_nan=False))
        if args.mode == "pilot" and not result["pilotPassed"]: raise SystemExit(2)
    except (ValueError, TypeError, KeyError, AttributeError) as error:
        print(json.dumps(dict(evaluationPassed=False,L4Passed=False,problems=["INVALID_EVIDENCE_STRUCTURE"])))
        raise SystemExit(1)
