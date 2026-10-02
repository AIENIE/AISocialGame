"""Read-only closure preparation. Reports are evidence, never execution grants."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys

from closure_identity import artifact
from shared_target import environment, matrix, component, resolve, sha

RUN_ID = "game-realism-v2-live-20260912"
MIGRATIONS = ("20260912_game_realism_v2.sql", "20260919_ai_turn_diagnostics.sql", "20260922_milestone_closure.sql")


def now():
    return datetime.now(timezone.utc).isoformat()


def check(status, reason, **evidence):
    return dict(status=status, reason=reason, collectedAt=now(), evidence=evidence)


def load_json(path):
    return strict_json(Path(path).read_text(encoding="utf-8-sig"))


def strict_json(text):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("DUPLICATE_JSON_KEY")
            value[key] = item
        return value
    return json.loads(text, object_pairs_hook=unique)


def ledger(path):
    raw = Path(path).read_bytes()
    if not raw.endswith(b"\n"):
        raise ValueError("INCOMPLETE_LEDGER")
    rows = [strict_json(line) for line in raw.decode("utf-8").splitlines()]
    if not rows:
        raise ValueError("EMPTY_EXISTING_LEDGER")
    for number, row in enumerate(rows, 1):
        if row.get("event") != "ATTEMPT_RESERVED" or type(row.get("comparisonAttempt")) is not int or row["comparisonAttempt"] != number:
            raise ValueError("INVALID_LEDGER_SEQUENCE")
    return dict(consumed=len(rows), sha256=sha(path), pilotNewMaximum=24, fullNewMaximum=360,
                requestedPilotCumulative=len(rows)+24, requestedFullCumulative=len(rows)+360,
                currentAuthorizedCeiling=None, historicalCeiling=90)


def version(root, manifest_path, jar):
    m = load_json(manifest_path)
    actual = artifact(root, jar, verify=True)
    if any(type(m.get(k)) is not int for k in ("evaluationSchemaVersion","sampleCount","maxDiscreteCalls","inputFormatVersion","memoryFormatVersion")):
        raise ValueError("INVALID_MANIFEST_NUMERIC_TYPE")
    for key in ("sourceFingerprint", "buildId"):
        if m.get(key) != actual[key]:
            raise ValueError("BUILD_MANIFEST_MISMATCH")
    if (m.get("evaluationSchemaVersion"), m.get("evaluationSetVersion"), m.get("evidenceKind"), m.get("sampleCount"), m.get("maxDiscreteCalls"), m.get("promptVersion"), m.get("inputFormatVersion"), m.get("memoryFormatVersion")) != (2,"closure-v2","REAL_MODEL",180,360,"social-v2.8",3,4) or not m.get("batchId"):
        raise ValueError("INVALID_FROZEN_MANIFEST")
    samples=m.get("samples", [])
    if len(samples)!=180 or len({x["id"] for x in samples})!=180:
        raise ValueError("INVALID_FROZEN_INVENTORY")
    if m != load_json(root/"backend/target/m1-m5-evaluation-manifest.json"):
        raise ValueError("CURRENT_BATCH_OR_INVENTORY_MISMATCH")
    return dict(**actual, batchId=m["batchId"], manifestSha256=sha(manifest_path))


def budget(values, db):
    limit_text=values.get("APP_AI_VALIDATION_CALL_LIMIT", "")
    limit=int(limit_text) if re.fullmatch(r"[1-9][0-9]*",limit_text) else None
    run=values.get("APP_AI_VALIDATION_RUN_ID")
    rows=db.get("budget",[]) if db.get("status")=="PASS" else []
    rows=[{k.lower():v for k,v in row.items()} for row in rows]
    consumed=rows[0].get("consumed") if len(rows)==1 and rows[0].get("id")==RUN_ID else None
    known=type(consumed) is int and consumed>=0
    ready=known and limit is not None and run==RUN_ID and consumed<=limit
    return check("PASS" if ready else "UNKNOWN", "BUDGET_READ_AND_CONFIGURATION_MATCH" if ready else "MISSING_OR_UNCONFIRMED_BUDGET",
                 expectedRunId=RUN_ID, configuredRunId=run if run==RUN_ID else None, consumed=consumed if known else None,
                 configuredCeiling=limit, configurationIsRunningEvidence=False,
                 remaining=limit-consumed if ready else None, rowPresent=(len(rows)==1 if db.get("status")=="PASS" else None),
                 budgetTablePresent=db.get("budgetTablePresent"), requiresFreshReadBeforeExecution=True)


def account_materials(path):
    if not path: return check("UNKNOWN","ACCOUNT_MATERIALS_NOT_SUPPLIED")
    values={}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.startswith('#'): continue
        key,separator,value=line.partition('=')
        if not separator or key in values: raise ValueError("INVALID_ACCOUNT_PROPERTIES")
        values[key]=value
    ids=[];names=[]
    for i in range(1,4):
        prefix=f"account.{i}."
        if not values.get(prefix+'username') or not values.get(prefix+'password') or not re.fullmatch(r'[1-9][0-9]*',values.get(prefix+'userId','')):
            return check("UNKNOWN","ACCOUNT_MATERIALS_INCOMPLETE")
        ids.append(values[prefix+'userId']);names.append(values[prefix+'username'])
    return check("PASS" if len(set(ids))==len(set(names))==3 else "FAIL","MATERIAL_STRUCTURE_ONLY_NOT_LOGIN_PROOF",accountCount=3,sessionCreated=False)


def migration_diff(root, db):
    rows=[{k.lower():v for k,v in row.items()} for row in db.get("columns",[])]
    present={(r["table_name"],r["column_name"]):r for r in rows}
    indexes=[{k.lower():v for k,v in row.items()} for row in db.get("indexes",[])]
    expected={}; required_indexes={}
    schema=(root/"backend/sql/schema.sql").read_text(encoding="utf-8")
    for table, body in re.findall(r"CREATE TABLE IF NOT EXISTS\s+`?(\w+)`?\s*\((.*?)\)\s*(?:ENGINE|;)",schema,re.S|re.I):
        # Split column definitions outside parentheses, including compact unquoted closure DDL.
        definitions=re.split(r",(?![^()]*\))",body)
        for line in definitions:
            col=re.fullmatch(r"\s*`?(\w+)`?\s+(\w+(?:\(\d+\))?)(.*)",line,re.S)
            if col and col[1].upper() not in ("PRIMARY","KEY","UNIQUE","INDEX","CONSTRAINT"):
                name,kind,tail=col.groups()
                kind={"boolean":"tinyint(1)","bool":"tinyint(1)"}.get(kind.lower(),kind.lower())
                expected[(table,name)]=(kind,"NO" if "NOT NULL" in tail or "PRIMARY KEY" in tail else "YES")
                if "PRIMARY KEY" in tail: required_indexes[(table,"PRIMARY")]=([name],0)
            m=re.search(r"(PRIMARY KEY|(?:UNIQUE )?(?:KEY|INDEX)\s+`?(\w+)`?)\s*\(([^)]+)\)",line)
            if m: required_indexes[(table,m[2] or "PRIMARY")]=([x.strip().strip('`') for x in m[3].split(',')],0 if m[1].startswith(('PRIMARY','UNIQUE')) else 1)
    missing=[];changed=[]
    for key,(kind,nullable) in expected.items():
        row=present.get(key)
        if row is None: missing.append(".".join(key))
        elif str(row["column_type"]).lower()!=kind or row["is_nullable"]!=nullable:
            changed.append(dict(column=".".join(key),expectedType=kind,actualType=row["column_type"],expectedNullable=nullable,actualNullable=row["is_nullable"]))
    actual_indexes={}
    for row in indexes: actual_indexes.setdefault((row["table_name"],row["index_name"]),[]).append((int(row["seq_in_index"]),row["column_name"]))
    missing_indexes=[".".join(k) for k,(v,unique) in required_indexes.items() if [x[1] for x in sorted(actual_indexes.get(k,[]))]!=v or any(int(r["non_unique"])!=unique for r in indexes if (r["table_name"],r["index_name"])==k)]
    # SQL remains unchanged. A column being present does not prove its backfill ran.
    known=db.get("status")=="PASS"
    plans=[]
    for i,name in enumerate(MIGRATIONS,1):
        affected = ([x for x in missing if x.startswith(("rooms.host_user_id","rooms.private_config","game_states.version","ai_persona_memories.approved_summary","ai_persona_memories.review_status","ai_turn_jobs.","ai_call_budgets.")) and not x.endswith(".diagnostics")] if i==1 else
                    [x for x in missing if x=="ai_turn_jobs.diagnostics"] if i==2 else [x for x in missing if x.startswith("ai_call_usage.")])
        plans.append(dict(order=i,file=name,sha256=sha(root/"backend/sql"/name),status="UNKNOWN" if not known else "REQUIRED" if affected else "STRUCTURE_PRESENT_REVIEW_DATA_EFFECTS",
                          missingColumns=affected if known else None,expectedChange=("Add v2 columns/tables and backfill host from valid seats; preserve history/budget" if i==1 else "Add nullable diagnostics; no history rewrite" if i==2 else "Add local call usage table/index; preserve cumulative budget")))
    return check("UNKNOWN" if not known else "FAIL" if missing or changed or missing_indexes else "PASS",
                 "DATABASE_NOT_READ" if not known else "SCHEMA_COMPARISON", migrations=plans,
                 missingColumns=missing if known else None,typeOrNullabilityDifferences=changed if known else None,
                 missingOrChangedIndexes=missing_indexes if known else None, expectedColumnCount=len(expected),
                 hostBackfillVerification="NOT_RUN_NO_SEAT_BODY_READ")


def database_read(root, env_path, target):
    if target.get("status")!="PASS":
        return dict(status="NOT_RUN",reason="TARGET_UNCONFIRMED")
    repo=Path.home()/".m2/repository"
    jars=[]
    for folder,name in (("com/mysql/mysql-connector-j","mysql-connector-j"),("com/fasterxml/jackson/core/jackson-databind","jackson-databind"),("com/fasterxml/jackson/core/jackson-core","jackson-core"),("com/fasterxml/jackson/core/jackson-annotations","jackson-annotations")):
        candidates=[p for p in (repo/folder).glob("*/*.jar") if re.fullmatch(r"\d+(\.\d+)+",p.parent.name) and p.name==f"{name}-{p.parent.name}.jar"]
        jars.append(str(max(candidates,key=lambda p:tuple(map(int,p.parent.name.split('.'))))))
    env=os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"): env.pop(key,None)
    proc=subprocess.run(["java.exe","--class-path",os.pathsep.join(jars),str(root/"scripts/windows/support/ClosurePreflightData.java"),str(env_path),target["jdbcUrl"],RUN_ID],capture_output=True,text=True,timeout=90,env=env)
    if proc.returncode: return dict(status="UNKNOWN",reason="DATABASE_COLLECTOR_FAILED")
    return json.loads(proc.stdout)


def network(catalog):
    results=[]
    # TLS handshakes only, never HTTP login, HMAC/RPC, cookies or request bodies.
    trust=Path(__file__).resolve().parents[1]/"local-trust"/"localcert-root-ca.crt"
    for name in ("ai-service","user-service"):
        c=component(catalog,name); local=c["endpoints"]["develop"]
        host=local["canonical_domain"]
        ports={x["port"] for x in local.get("listeners",[]) if x.get("protocol") in ("https","grpc-tls") and x.get("port")}
        for port in sorted(ports):
            if port is None: continue
            item=dict(componentId=name,host=host,port=port)
            try:
                item["addresses"]=sorted({a[4][0] for a in socket.getaddrinfo(host,port,type=socket.SOCK_STREAM)})
                with socket.create_connection((host,port),timeout=5) as tcp:
                    with ssl.create_default_context(cafile=str(trust)).wrap_socket(tcp,server_hostname=host) as tls:
                        cert=tls.getpeercert();item.update(protocol=tls.version(),certificateExpires=cert.get("notAfter"))
                results.append(check("PASS","DNS_TCP_TLS_ONLY",**item))
            except Exception: results.append(check("UNKNOWN","DNS_TCP_OR_VERIFIED_TLS_FAILED",**item))
    return check("PASS" if results and all(r["status"]=="PASS" for r in results) else "UNKNOWN","TRANSPORT_IS_NOT_AUTHORIZATION",endpoints=results)


def proposal(report):
    checks=report["checks"]; comparison=checks["comparisonLedger"]["evidence"]
    return dict(documentKind="UNAUTHORIZED_EXECUTION_PROPOSAL",schemaVersion=1,createdAt=now(),authorized=False,
                preflightSha256=None,version=checks["version"]["evidence"],target=checks["databaseTarget"]["evidence"],
                migrations=checks["migrationDiff"]["evidence"].get("migrations",[]),
                caller=dict(currentStatus="UNKNOWN",proposedTransition="OFFLINE -> ACTIVE only after explicit authorization; finally OFFLINE readback"),
                comparisonBudget=comparison,liveBudget=checks["liveBudget"]["evidence"],
                liveEstimate=dict(status="UNKNOWN",newRequestMaximum=None,nicknameSeatCount=16,nicknameRequestMaximum=16,
                                  basis="Current six-game fixture: undercover 4/4, werewolf 6/6, soup 4/4 players; each game has 1 or 3 humans, totaling 16 AI seats. AiNameService makes at most one RPC per seat (no retry). Generation <=2 RPC per AI opportunity; total game opportunity counts are not bounded by this estimate. Historical 105/210 is not a current balance.",
                                  required="Freeze player counts, round caps, AI opportunity counts and nickname retry cap; read persistent consumed before proposing a cumulative maximum."),
                backup=dict(location=None,requirement="Approved external consistent backup of the verified target; record restore test and SQL hashes."),
                stopWrites="Stop app/scheduler writers for this target before backup and migration.",
                recovery=["Stop new requests and preserve all ledgers and in-flight evidence.","Restore previous application/configuration; retain additive columns/tables.","Database restore only from approved backup with reconciliation of all later consumed requests; never reset budget."],
                executionConditions=["Resolve every FAIL/UNKNOWN and obtain current environment confirmation.","Complete ordinary-user login in the later authorized stage.","Approve exact migration target, backup and caller transition.","Approve separate cumulative comparison/live limits using freshly read consumed values.","Re-read caller, budget, matrix and effective configuration immediately before execution."],
                executionPermitted=False,L4Passed=False)


def main(args):
    root=Path(args.root).resolve(); out=Path(args.output).resolve()
    if out.is_relative_to(root) or out.exists(): raise ValueError("NEW_EXTERNAL_OUTPUT_REQUIRED")
    for p in (args.environment,args.ledger,args.manifest):
        if Path(p).resolve().is_relative_to(root): raise ValueError("EXTERNAL_INPUT_REQUIRED")
    checks={}
    def attempt(name, fn):
        try: checks[name]=fn()
        except Exception: checks[name]=check("UNKNOWN","INPUT_UNAVAILABLE_INVALID_OR_CHECK_FAILED")
    attempt("version",lambda:check("PASS","FROZEN_SOURCE_JAR_MANIFEST_MATCH",**version(root,Path(args.manifest),Path(args.jar))))
    attempt("comparisonLedger",lambda:check("PASS","EXISTING_CONTIGUOUS_LEDGER",**ledger(args.ledger)))
    values={}; catalog={}; target=dict(status="UNKNOWN",reason="INPUT_UNAVAILABLE")
    try:
        values=environment(args.environment);catalog=matrix(args.matrix)
        target=resolve(catalog,values,sha(args.matrix))
    except Exception: pass
    if args.matrix_invalid: target=dict(status="UNKNOWN",reason="MATRIX_VALIDATION_FAILED")
    checks["matrix"]=check("UNKNOWN" if args.matrix_invalid or not catalog else "PASS","CANONICAL_MATRIX_VALIDATOR",sha256=sha(args.matrix))
    checks["databaseTarget"]=check(target["status"],target["reason"],**{k:v for k,v in target.items() if k not in ("status","reason","jdbcUrl")})
    try: db=database_read(root,Path(args.environment),target) if checks["version"]["status"]=="PASS" else dict(status="NOT_RUN",reason="VERSION_OR_BATCH_UNCONFIRMED")
    except Exception: db=dict(status="UNKNOWN",reason="DATABASE_CHECK_FAILED")
    checks["database"]=check(db["status"],db.get("reason","METADATA_SELECT_ONLY"),**{k:v for k,v in db.items() if k not in ("status","reason")})
    if db.get("status")=="PASS" and len(db.get("server",[]))==1:
        server={k.lower():v for k,v in db["server"][0].items()}
        baseline=dict(version="8.0.45",charset="utf8mb4",collation="utf8mb4_unicode_ci",isolationlevel="REPEATABLE-READ")
        differences={k:dict(expected=v,actual=server.get(k)) for k,v in baseline.items() if server.get(k)!=v}
        checks["databaseBaseline"]=check("FAIL" if differences else "PASS","COMPARED_WITH_ISOLATED_MYSQL_GATE",differences=differences,timezones="Observed separately; no inferred eight-hour correction")
    else: checks["databaseBaseline"]=check("NOT_RUN","DATABASE_NOT_READ")
    attempt("migrationDiff",lambda:migration_diff(root,db))
    checks["liveBudget"]=budget(values,db)
    if args.matrix_invalid: checks["network"]=check("NOT_RUN","MATRIX_VALIDATION_FAILED")
    else: attempt("network",lambda:network(catalog))
    checks["caller"]=check("UNKNOWN","EXISTING_READ_PATH_REQUIRES_ADMIN_LOGIN_NO_REUSABLE_SESSION_SUPPLIED",expectedCallerId=33,expectedCaller="aisocialgame-realism-v2-20260912",historicalStatus="OFFLINE",currentStatus=None)
    checks["modelConfiguration"]=check("PASS" if values.get("APP_AI_DEFAULT_MODEL")=="deepseek-flash" else "UNKNOWN","LOCAL_CONFIGURATION_ONLY_REMOTE_STATUS_UNCONFIRMED",configuredModel="deepseek-flash" if values.get("APP_AI_DEFAULT_MODEL")=="deepseek-flash" else None)
    checks["authenticatedRead"]=check("NOT_RUN","CALLER_ACTIVATION_AND_NEW_LOGIN_OUT_OF_SCOPE")
    callback=values.get("SSO_CALLBACK_URL")=="https://localsocialgame.testhut.top/sso/callback"
    checks["loginConfiguration"]=check("PASS" if callback else "UNKNOWN","CONFIGURATION_ONLY_NOT_LOGIN_PROOF",callbackMatches=callback,remoteAllowlistVerified=False)
    attempt("accountMaterials",lambda:account_materials(args.accounts))
    report=dict(schemaVersion=1,documentKind="READ_ONLY_PREFLIGHT",collectedAt=now(),checks=checks,
                readyForExecution=False,materialsComplete=all(x["status"]=="PASS" for k,x in checks.items() if k!="authenticatedRead"),
                realCalls=0,loginSessionsCreated=0,sharedMutations=0,L4Passed=False,
                freshness="Point-in-time evidence only; mutable state must be re-read before execution.")
    out.mkdir(parents=True)
    report_path=out/"preflight.json";report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    draft=proposal(report);draft["preflightSha256"]=sha(report_path)
    (out/"execution-proposal.json").write_text(json.dumps(draft,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(dict(report=str(report_path),states={k:v["status"] for k,v in checks.items()},L4Passed=False)))


if __name__=="__main__":
    if len(sys.argv)==3 and sys.argv[1]=='--ledger-summary':
        try:print(json.dumps(ledger(sys.argv[2])))
        except Exception:print('{"status":"FAIL","reason":"INVALID_EXISTING_LEDGER"}');raise SystemExit(1)
        raise SystemExit(0)
    p=argparse.ArgumentParser()
    for name in ("root","matrix","environment","ledger","manifest","jar","output"):p.add_argument("--"+name,required=True)
    p.add_argument("--accounts")
    p.add_argument("--matrix-invalid",action="store_true")
    try:main(p.parse_args())
    except Exception:print('{"status":"FAIL","reason":"PREFLIGHT_INPUT_OR_OUTPUT_ERROR"}');raise SystemExit(1)
