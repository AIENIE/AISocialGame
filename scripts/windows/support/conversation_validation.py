"""96-sample current-version profile. Offline by default; never invokes a service or changes a ledger."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import hashlib
from closure_metrics import (HEADER, META, STEPS, GAMES, DIMENSIONS, keyed, same_header,
                            base_checks, sequence_checks, number, nonblank, references_valid)
from closure_preflight import load_json, ledger
from closure_identity import artifact

SET = "conversation-validation-v1"
CHECKS = ("interactionNeed", "proportionateLength", "stopsWhenComplete")
SCENARIOS = {
    "undercover": ("first_description", "direct_question", "peer_correction", "runoff_defense"),
    "werewolf": ("ordinary_stance", "identity_question", "counterevidence", "no_new_information"),
    "turtle_soup": ("no_new_contribution", "human_prepares_question", "verdict_excludes_hypothesis", "specific_question"),
}
PILOTS = {"undercover:direct_question", "werewolf:counterevidence", "turtle_soup:human_prepares_question"}
PERSONAS = ("ai1", "ai2", "ai3", "ai4")

def conversation_ledger(path):
    # An explicitly authorized new run has no historical entries to fabricate.
    if Path(path).read_bytes()==b"":
        return dict(consumed=0,sha256=sha(path))
    return ledger(path)

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def require(condition, reason):
    if not condition: raise ValueError(reason)

def inventory(manifest):
    require(same_header(manifest, expected_set=SET), "INVALID_HEADER")
    for k, v in dict(sampleCount=96, maxDiscreteCalls=192, inputFormatVersion=3, memoryFormatVersion=4).items():
        require(type(manifest.get(k)) is int and manifest[k] == v, "INVALID_PROFILE")
    require(manifest.get("promptVersion") == "social-v2.8", "INVALID_PROMPT")
    expected = {}
    for game, scenarios in SCENARIOS.items():
        for persona in PERSONAS:
            for scenario in scenarios:
                sid = game+":"+scenario
                key = "conversation:"+sid+":"+persona
                expected[key] = dict(id=key, group="SINGLE", gameId=game, personaId=persona, scenarioId=sid, sequenceId="", stepId="")
            for step, stage in enumerate(STEPS, 1):
                key = f"sequence:{game}:{persona}:{step}"
                expected[key] = dict(id=key, group="SEQUENCE", gameId=game, personaId=persona, scenarioId="continuity-v2", sequenceId=game+":continuity-v2", stepId=stage)
    actual = keyed(manifest.get("samples"), "MANIFEST")
    require(actual == expected, "INVENTORY_MISMATCH")
    pilots = manifest.get("pilotIds")
    wanted = {k for k,v in expected.items() if v["scenarioId"] in PILOTS}
    require(isinstance(pilots,list) and len(pilots)==12 and set(pilots)==wanted, "PILOT_INVENTORY_MISMATCH")
    return actual

def frozen(manifest_path, bundle_path):
    m,b = load_json(manifest_path),load_json(bundle_path)
    inventory(m)
    require(m.get("bundleSha256")==sha(bundle_path), "BUNDLE_HASH_MISMATCH")
    require(same_header(m,b,expected_set=SET), "BUNDLE_VERSION_MISMATCH")
    singles=keyed(b.get("singles"),"FROZEN_SINGLES")
    require(set(singles)=={k for k,v in inventory(m).items() if v["group"]=="SINGLE"},"FROZEN_SINGLE_MATRIX")
    sequences=b.get("sequences",[])
    require(len(sequences)==12 and {(s["gameId"],s["personaId"]) for s in sequences}=={(g,p) for g in GAMES for p in PERSONAS},"FROZEN_SEQUENCE_MATRIX")
    require(all(isinstance(s.get("state"),dict) and isinstance(s.get("observation"),dict) and isinstance(s.get("input"),dict) and nonblank(s.get("now")) for s in list(singles.values())+sequences),"INCOMPLETE_FROZEN_STATE")
    return m,b

def resume_check(m,e):
    require(same_header(m,e,expected_set=SET) and m.get("bundleSha256")==e.get("bundleSha256"),"RESUME_VERSION_MISMATCH")
    planned=inventory(m);rows=keyed(e.get("samples"),"EVIDENCE")
    require(set(rows)==set(planned),"RESUME_INVENTORY_MISMATCH")
    for key,row in rows.items():
        require(all(row.get(k)==planned[key][k] for k in META),"RESUME_METADATA_MISMATCH")
        require(row.get("status") in ("NOT_RUN","STARTED","NO_RESPONSE","GENERATED","LOCAL_FALLBACK"),"RESUME_STATUS_INVALID")
        if row["status"] in ("GENERATED","LOCAL_FALLBACK"):
            fallback=row.get("decision",{}).get("fallback")
            require(type(fallback) is bool and (row["status"]=="LOCAL_FALLBACK")==fallback,"RESUME_FALLBACK_INVALID")
    return rows

def measure(m,b,e,r,evidence_hash,pilot=False):
    all_planned=inventory(m);all_rows=resume_check(m,e)
    wanted=set(m["pilotIds"]) if pilot else set(all_planned)
    selected=dict(e,samples=[v for k,v in all_rows.items() if k in wanted])
    planned,rows,reviews,errors=base_checks(m,selected,r,wanted,expected_set=SET)
    if r.get("evidenceSha256")!=evidence_hash or not evidence_hash: errors.append("REVIEW_EVIDENCE_HASH_MISMATCH")
    if pilot and any(v["status"]!="NOT_RUN" for k,v in all_rows.items() if k not in wanted): errors.append("NONPILOT_ATTEMPT_BEFORE_REVIEW")
    singles=keyed(b["singles"],"FROZEN_SINGLES")
    seq={(s["gameId"],s["personaId"]):s for s in b["sequences"]}
    reservation_ids=set();request_ids=set()
    for key in wanted:
        row=rows[key];meta=planned[key];d=row.get("decision",{});cover=row.get("coverage",{});obs=row.get("observation",{})
        if row.get("status")!="GENERATED" or d.get("fallback") is not False: errors.append(key+":NORMAL_GENERATION_REQUIRED")
        if m["evidenceKind"]=="REAL_MODEL":
            reservations=row.get("reservations",[]);calls=d.get("diagnostics",{}).get("calls")
            if type(calls) is not int or calls not in (1,2) or not isinstance(reservations,list) or len(reservations)!=calls: errors.append(key+":RPC_RESERVATIONS_MISSING")
            for receipt in reservations if isinstance(reservations,list) else []:
                if receipt.get("creditState")!="SETTLED" or not nonblank(receipt.get("creditReservationId")) or not nonblank(receipt.get("budgetId")): errors.append(key+":PERSISTENT_CREDIT_RECEIPT_MISSING")
                ordinal=receipt.get("comparisonAttempt");request=receipt.get("requestId")
                if type(ordinal) is not int or ordinal<=0 or ordinal in reservation_ids or not nonblank(request) or request in request_ids: errors.append(key+":INVALID_RPC_RESERVATION")
                reservation_ids.add(ordinal);request_ids.add(request)
        if cover.get("status")!="APPLIED" or cover.get("action")!=d.get("action") or cover.get("origin")!="MODEL" or cover.get("phase")!=obs.get("phase") or type(cover.get("round")) is not int or cover.get("round")!=obs.get("round") or not isinstance(row.get("memoryAfter"),dict): errors.append(key+":SUBMISSION_NOT_PROVEN")
        seed=singles[key] if meta["group"]=="SINGLE" else seq[(meta["gameId"],meta["personaId"])] if meta["stepId"]=="INITIAL" else None
        if seed and (row.get("observation")!=seed["observation"] or row.get("input")!=seed["input"]): errors.append(key+":FROZEN_INPUT_MISMATCH")
        for name in CHECKS:
            c=reviews.get(key,{}).get("conversation",{}).get(name,{})
            if c.get("verdict")!="PASS" or not nonblank(c.get("reason")) or not references_valid(c.get("evidence"),rows,key): errors.append(key+":"+name+":NOT_PASSED")
        for dim in DIMENSIONS:
            if not nonblank(reviews.get(key,{}).get("scoreReasons",{}).get(dim)): errors.append(key+":"+dim+":SCORE_REASON_MISSING")
    if not pilot: errors.extend(sequence_checks(planned,rows))
    groups={}
    for group in ("SINGLE",) if pilot else ("SINGLE","SEQUENCE"):
        ids=[k for k in wanted if planned[k]["group"]==group]
        normal=[k for k in ids if rows[k].get("status")=="GENERATED" and rows[k].get("decision",{}).get("fallback") is False]
        known=[k for k in ids if type(rows[k].get("decision",{}).get("fallback")) is bool]
        result=dict(planned=len(ids), normal=len(normal), applied=sum(rows[k].get("coverage",{}).get("status")=="APPLIED" for k in ids),
                    fallbackKnown=len(known),fallbackUnknown=len(ids)-len(known),fallback=sum(rows[k]["decision"]["fallback"] for k in known),
                    repaired=sum(rows[k].get("decision",{}).get("diagnostics",{}).get("repaired") is True for k in normal),
                    noResponse=sum(rows[k]["status"] in ("NO_RESPONSE","STARTED") for k in ids),notRun=sum(rows[k]["status"]=="NOT_RUN" for k in ids),means={})
        for persona in ("ALL",) if pilot else ("ALL",)+PERSONAS:
            subset=[k for k in ids if persona=="ALL" or planned[k]["personaId"]==persona];metrics={}
            for dim in DIMENSIONS:
                values=[reviews[k]["scores"][dim] for k in subset if k in normal and number(reviews.get(k,{}).get("scores",{}).get(dim),1,5) and references_valid(reviews[k].get("evidence",{}).get(dim),rows,k)]
                metrics[dim]=dict(n=len(values),mean=sum(values)/len(values) if values else None)
                if len(values)!=len(subset) or not values or sum(values)/len(values)<4: errors.append(f"{group}:{persona}:{dim}:QUALITY_GATE")
            result["means"][persona]=metrics
        groups[group]=result
    attempts=[a for row in rows.values() for a in row.get("decision",{}).get("diagnostics",{}).get("attempts",[])]
    measurements={}
    for field in ("rpcMs","inputBytes","outputBytes","promptTokens","completionTokens"):
        values=[a[field] for a in attempts if number(a.get(field))]
        if any(a.get(field) is not None and not number(a.get(field)) for a in attempts): errors.append("INVALID_MEASUREMENT:"+field)
        measurements[field]=dict(n=len(values),missing=len(attempts)-len(values),mean=sum(values)/len(values) if values else None)
    return dict(algorithmPassed=not errors,qualityPassed=not errors and m["evidenceKind"]=="REAL_MODEL",L4Passed=False,groups=groups,measurements=measurements,problems=sorted(set(errors)),reviewType="CODING_AGENT_NOT_INDEPENDENT_HUMAN_BLIND_REVIEW")

def grant_check(m,g,ledger_path,manifest_path,bundle_path,jar_hash,consumed,phase):
    require(g.get("authorizationStatus")=="APPROVED" and all(nonblank(g.get(k)) for k in ("approvedBy","approvalReference","callerId")),"EXPLICIT_GRANT_REQUIRED")
    require(g.get("callerLifecycle")=="SHARED" and g.get("enableOriginalCaller") is False and g.get("disableCallerOnExit") is False,"SHARED_CALLER_MUST_REMAIN_UNCHANGED")
    require(type(g.get("callerRecordId")) is int and g["callerRecordId"]>0,"VALID_CALLER_RECORD_REQUIRED")
    require(datetime.fromisoformat(g["expiresAt"].replace("Z","+00:00"))>datetime.now(timezone.utc),"GRANT_EXPIRED")
    for key in ("batchId","sourceFingerprint","buildId"):
        require(g.get(key)==m[key],"GRANT_VERSION_MISMATCH")
    require(g.get("model")=="deepseek-flash" and Path(g["comparisonLedger"]).resolve()==Path(ledger_path).resolve(),"GRANT_TARGET_MISMATCH")
    require(g.get("manifestSha256")==sha(manifest_path) and g.get("bundleSha256")==sha(bundle_path) and g.get("artifactSha256")==jar_hash,"GRANT_ARTIFACT_MISMATCH")
    base=g.get("baselineConsumed");cap=g.get("cumulativeComparisonLimit")
    require(type(base) is int and type(cap) is int and 0<=base<=consumed<cap<=base+192,"GRANT_LIMIT_INVALID")
    require(base!=0 or g.get("ledgerOrigin")=="NEW_AUTHORIZED_BATCH","NEW_LEDGER_AUTHORIZATION_REQUIRED")
    lines=Path(ledger_path).read_bytes().splitlines(keepends=True)
    require(hashlib.sha256(b"".join(lines[:base])).hexdigest()==g.get("baselineLedgerSha256"),"LEDGER_PREFIX_CHANGED")
    variant=SET+":"+m["batchId"]
    pilot_calls=sum(1 for line in lines[base:] if (row:=json.loads(line)).get("variant")==variant and row.get("scenarioId") in m["pilotIds"])
    require(phase!="PILOT" or pilot_calls<24,"PILOT_LIMIT_EXHAUSTED")
    return g

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--mode",choices=("preflight","gate","report","pilot"),default="preflight")
    for name in ("manifest","bundle","ledger","root","jar","grant","evidence","review","prior","prerequisites"):
        p.add_argument("--"+name)
    p.add_argument("--phase",choices=("PILOT","REMAINING"),default="PILOT")
    args=p.parse_args();m,b=frozen(args.manifest,args.bundle)
    if args.mode in ("report","pilot"):
        result=measure(m,b,load_json(args.evidence),load_json(args.review),sha(args.evidence),args.mode=="pilot")
        print(json.dumps(result,ensure_ascii=True,allow_nan=False,indent=2))
        return 0 if result["qualityPassed"] else 2
    actual=artifact(Path(args.root),Path(args.jar),verify=True)
    require(all(m[k]==actual[k] for k in ("sourceFingerprint","buildId")),"CURRENT_BUILD_MISMATCH")
    summary=conversation_ledger(args.ledger);g=None
    if args.mode=="gate":
        require(m["evidenceKind"]=="REAL_MODEL","REAL_MANIFEST_REQUIRED")
        g=grant_check(m,load_json(args.grant),args.ledger,args.manifest,args.bundle,actual["artifactSha256"],summary["consumed"],args.phase)
        proof=load_json(args.prerequisites)
        age=(datetime.now(timezone.utc)-datetime.fromisoformat(proof["collectedAt"].replace("Z","+00:00"))).total_seconds()
        require(0<=age<=900 and proof.get("batchId")==m["batchId"] and proof.get("callerId")==g["callerId"] and proof.get("callerRecordId")==g["callerRecordId"] and proof.get("callerState")=="ACTIVE" and proof.get("model")=="deepseek-flash" and proof.get("tlsVerified") is True and proof.get("matrixVerified") is True and proof.get("persistentCreditEscrow") is True and nonblank(proof.get("readbackEvidenceSha256")),"FRESH_CALLER_AND_TLS_READBACK_REQUIRED")
        if args.prior: resume_check(m,load_json(args.prior))
        if args.phase=="REMAINING":
            result=measure(m,b,load_json(args.evidence),load_json(args.review),sha(args.evidence),True)
            require(result["qualityPassed"],"PILOT_NOT_PASSED")
            if args.prior:
                prior=keyed(load_json(args.prior)["samples"],"PRIOR");pilot_rows=keyed(load_json(args.evidence)["samples"],"PILOT")
                require(all(prior[k]==pilot_rows[k] for k in m["pilotIds"]),"PILOT_CHANGED_ON_RESUME")
    result=dict(status="GATE_PASSED" if g else "UNAUTHORIZED_DRAFT",realCalls=0,L4Passed=False,**actual,batchId=m["batchId"],
                manifestSha256=sha(args.manifest),bundleSha256=sha(args.bundle),baselineConsumed=summary["consumed"],baselineLedgerSha256=summary["sha256"],
                comparisonLedger=str(Path(args.ledger).resolve()),pilotMaximumNewRequests=24,maximumNewRequests=192,
                proposedPilotCumulativeLimit=summary["consumed"]+24,proposedFullCumulativeLimit=summary["consumed"]+192,
                authorizationStatus="NOT_AUTHORIZED",approvedBy=None,approvalReference=None,expiresAt=None,
                model="deepseek-flash",callerRecordId=None,callerId=None,callerState="UNKNOWN",callerLifecycle="SHARED",enableOriginalCaller=False,disableCallerOnExit=False)
    print(json.dumps(result,ensure_ascii=True,indent=2));return 0

if __name__=="__main__":
    try: raise SystemExit(main())
    except Exception:
        print(json.dumps(dict(status="FAIL",qualityPassed=False,L4Passed=False,reason="INVALID_OR_UNAUTHORIZED_INPUT")))
        raise SystemExit(1)
