import copy
import json
from pathlib import Path
import unittest
from closure_metrics import DIMENSIONS, GAMES, STEPS, measure, pilot

class ClosureMetricsTest(unittest.TestCase):
    def full_case(self):
        header = dict(evaluationSchemaVersion=2,evaluationSetVersion="closure-v2",batchId="offline-test",sourceFingerprint="a"*64,buildId="b"*64,
                      promptVersion="social-v2.8",inputFormatVersion=3,memoryFormatVersion=4,evidenceKind="SYNTHETIC_TEST")
        planned=[]; rows=[]; reviews=[]
        for game in GAMES:
            for group,count in (("SINGLE",10),("SEQUENCE",4)):
                for i in range(count):
                    for p in range(1,5):
                        key=f"{group}:{game}:{i}:ai{p}"
                        meta=dict(id=key,group=group,gameId=game,personaId=f"ai{p}",scenarioId=f"case-{i}" if group=="SINGLE" else "continuity",
                                  sequenceId="" if group=="SINGLE" else game+"-continuity",stepId="" if group=="SINGLE" else STEPS[i])
                        phase = ({"undercover":("DESCRIPTION","RESPONSE","VOTING","DESCRIPTION"),"werewolf":("DAY_DISCUSS","DAY_INTERACTION","DAY_VOTE","DAY_DISCUSS"),"turtle_soup":("QUESTIONING",)*4}[game][i] if group=="SEQUENCE" else "DISCUSSION")
                        planned.append(meta); event=f"{game}:ai{p}:event-{i}"
                        row=dict(meta,status="GENERATED",decision=dict(fallback=False,action=dict(type="SPEAK",content="具体回应")),observation=dict(phase=phase,round=1+i//3,events=[dict(eventId=f"{game}:ai{p}:event-{j}",message="visible") for j in range(i)]))
                        if group=="SEQUENCE": row['coverage']=dict(status="APPLIED",origin="MODEL",stepId=STEPS[i],phase=phase,round=1+i//3,cycle=i+1,producedEventIds=[event],requiredVisibleEventIds=[] if i==0 else [f"{game}:ai{p}:event-{i-1}"],action=row['decision']['action'])
                        rows.append(row); ref=dict(sampleId=key,pointer="/decision/action/content")
                        reviews.append(dict(id=key,visibleInformation="NO_OBSERVED_VIOLATION",scores={d:4 for d in DIMENSIONS},evidence={d:[ref] for d in DIMENSIONS}))
        for i in range(12):
            meta=dict(id=f"host-{i}",group="HOST",gameId="turtle_soup",personaId="HOST",scenarioId=f"host-case-{i}",sequenceId="",stepId="")
            planned.append(meta);rows.append(dict(meta,status="GENERATED",decision=dict(fallback=False,action=dict(type="HOST_VERDICT",content="是"))))
            reviews.append(dict(id=meta['id'],visibleInformation="NO_OBSERVED_VIOLATION",hostCorrect=True,hostEvidence=[dict(sampleId=meta['id'],pointer="/decision/action/content")]))
        comparisons={g:{b:dict(verdict="PASS",assessment="测试样本中有具体行为差异",references=[dict(sampleId=f"SINGLE:{g}:0:ai{p}",pointer="/decision/action/content") for p in (1,2)]) for b in ("questioning","siding","revision","commitment")} for g in GAMES}
        scenarios=[dict(gameId=g,humanCount=h,status="passed",roomId=f"room-{g}-{h}",archiveId=f"archive-{g}-{h}",privacyViolations=[],winner="SOLVED",contractChecks=dict(successfulScriptedSolve=True)) for g in GAMES for h in (1,3)]
        jobs=[dict(id=f"job-{i}",roomId=s['roomId'],instanceId=s['archiveId'],status="SUCCEEDED",terminalStatus="SUCCEEDED",terminalReason="MODEL_ACTION_APPLIED",buildId=header['buildId'],sourceFingerprint=header['sourceFingerprint'],promptVersion=header['promptVersion']) for i,s in enumerate(scenarios)]
        traces=[dict(id=f"trace-{i}",jobId=j['id'],fallback=False,**{k:j[k] for k in ('roomId','instanceId','buildId','sourceFingerprint','promptVersion')}) for i,j in enumerate(jobs)]
        runtime=dict(header,evidenceSha256="file-hash",instances=sorted([dict(roomId=s['roomId'],archiveId=s['archiveId']) for s in scenarios],key=lambda s:(s['roomId'],s['archiveId'])),taskLinks=jobs,traceLinks=traces,jobs=dict(statusCounts=dict(SUCCEEDED=6)),flags=[])
        manifest=dict(header,samples=planned,pilotIds=[f"SINGLE:{g}:0:ai{p}" for g in GAMES for p in range(1,5)])
        return manifest,dict(header,samples=rows),dict(header,samples=reviews,personaComparisons=comparisons,evidenceSha256="decision-hash"),dict(header,scenarios=scenarios),runtime,"file-hash","decision-hash"

    def test_complete_synthetic_evidence_passes_algorithm_never_real_gate(self):
        r=measure(*self.full_case());self.assertTrue(r['finalAlgorithmPassed'],r);self.assertFalse(r['L4Passed']);self.assertFalse(r['evaluationPassed'])

    def test_real_complete_evidence_can_pass_and_missing_games_cannot(self):
        args=self.full_case()
        for obj in args[:5]:obj['evidenceKind']='REAL_MODEL'
        self.assertTrue(measure(*args)['L4Passed']);self.assertFalse(measure(*args[:3])['L4Passed'])

    def test_fallback_missing_types_and_status_conflicts(self):
        for value in (None,0,1,"false",{},[]):
            args=self.full_case();args[1]['samples'][0]['decision']['fallback']=value
            r=measure(*args);self.assertFalse(r['algorithmPassed']);self.assertEqual(1,r['groups']['SINGLE']['fallbackUnknown'])
            self.assertEqual(119,r['groups']['SINGLE']['completed'])
        args=self.full_case();del args[1]['samples'][0]['decision']['fallback'];self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();args[1]['samples'][0]['status']='LOCAL_FALLBACK';self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();args[1]['samples'][0]['status']='NO_RESPONSE';self.assertFalse(measure(*args)['algorithmPassed'])

    def test_metadata_duplicate_unknown_and_missing(self):
        for field in ('group','gameId','personaId','scenarioId','sequenceId','stepId'):
            args=self.full_case();args[1]['samples'][0][field]='wrong';self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();args[1]['samples'].append(args[1]['samples'][0]);self.assertRaises(ValueError,measure,*args)
        args=self.full_case();args[1]['samples'].pop();self.assertFalse(measure(*args)['algorithmPassed'])

    def test_numeric_inputs_are_not_coerced(self):
        for value in (True,float('nan'),float('inf'),-1,6,'4'):
            args=self.full_case();args[2]['samples'][0]['scores']['naturalness']=value;self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();args[1]['samples'][0]['decision']['diagnostics']=dict(attempts=[dict(rpcMs=True)]);self.assertFalse(measure(*args)['algorithmPassed'])

    def test_runtime_identity_mismatch(self):
        for field in ('sourceFingerprint','buildId','batchId','evidenceSha256'):
            args=self.full_case();args[4][field]='unrelated';self.assertFalse(measure(*args)['finalAlgorithmPassed'])
        args=self.full_case();args[4]['instances'][0]['archiveId']='other-game';self.assertFalse(measure(*args)['finalAlgorithmPassed'])
        args=self.full_case();args[4]['taskLinks'][0]['instanceId']='same-room-other-game';self.assertFalse(measure(*args)['finalAlgorithmPassed'])

    def test_runtime_trace_completeness_and_counts(self):
        for variant in ('missing','duplicate','orphan','failed','zero','counts','fallback'):
            args=self.full_case();rt=args[4]
            if variant=='missing':rt['traceLinks'].pop()
            if variant=='duplicate':rt['traceLinks'].append(dict(rt['traceLinks'][0],id='another-trace'))
            if variant=='orphan':rt['traceLinks'][0]['jobId']='missing-job'
            if variant=='failed':rt['taskLinks'][0]['status']='FAILED'
            if variant=='zero':rt['taskLinks']=[];rt['traceLinks']=[]
            if variant=='counts':rt['jobs']['statusCounts']['SUCCEEDED']=100
            if variant=='fallback':del rt['traceLinks'][0]['fallback']
            self.assertFalse(measure(*args)['finalAlgorithmPassed'],variant)

    def test_persona_conclusions_and_references(self):
        for verdict in (None,'FAIL','INSUFFICIENT_EVIDENCE',''):
            args=self.full_case();args[2]['personaComparisons']['undercover']['questioning']['verdict']=verdict;self.assertFalse(measure(*args)['algorithmPassed'])
        for ref in (dict(sampleId='SINGLE:werewolf:0:ai2',pointer='/decision/action/content'),dict(sampleId='SINGLE:undercover:1:ai2',pointer='/decision/action/content'),dict(sampleId='SINGLE:undercover:0:ai2',eventId='invisible'),dict(sampleId='SINGLE:undercover:0:ai2',pointer='/decision/nonexistent')):
            args=self.full_case();args[2]['personaComparisons']['undercover']['questioning']['references'][1]=ref;self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();args[2]['samples'][0]['evidence']['naturalness']='arbitrary nonempty text';self.assertFalse(measure(*args)['algorithmPassed'])

    def test_sequence_references_use_metadata_not_id_prefix(self):
        args=self.full_case();args[2]['personaComparisons']['undercover']['questioning']['references']=[dict(sampleId=f'SEQUENCE:undercover:1:ai{p}',eventId=f'undercover:ai{p}:event-0') for p in (1,2)]
        self.assertTrue(measure(*args)['algorithmPassed'])

    def test_sequence_missing_action_or_counterevidence(self):
        for step in ('COUNTEREVIDENCE','CONTINUITY'):
            args=self.full_case();row=next(r for r in args[1]['samples'] if r['stepId']==step)
            row['observation']['events']=[];self.assertFalse(measure(*args)['algorithmPassed'])
        args=self.full_case();row=next(r for r in args[1]['samples'] if r['stepId']=='ACTION');row['coverage']['status']='FAILED';self.assertFalse(measure(*args)['algorithmPassed'])

    def test_pilot_uses_same_strict_checks(self):
        args=self.full_case();m,e,r=args[:3];wanted=set(m['pilotIds']);e['samples']=[x for x in e['samples'] if x['id'] in wanted];r['samples']=[x for x in r['samples'] if x['id'] in wanted];r['status']='PASS'
        self.assertTrue(pilot(m,e,r)['algorithmPassed']);self.assertFalse(pilot(m,e,r)['pilotPassed'])
        del e['samples'][0]['decision']['fallback'];self.assertFalse(pilot(m,e,r)['algorithmPassed'])

    def test_actual_rule_sequence_export_matches_consumer(self):
        path=Path(__file__).resolve().parents[3]/'backend/target/closure-sequence-offline-evidence.json'
        if not path.exists(): self.skipTest('Run ClosureSequenceRunnerTest to produce rule evidence first')
        evidence=json.loads(path.read_text(encoding='utf-8'))
        manifest=dict(evidence,samples=[{k:r[k] for k in ('id','group','gameId','personaId','scenarioId','sequenceId','stepId')} for r in evidence['samples']])
        review=dict(evidence,samples=[dict(id=r['id'],visibleInformation='NO_OBSERVED_VIOLATION',scores={},evidence={}) for r in evidence['samples']],evidenceSha256='offline')
        result=measure(manifest,evidence,review,evidence_sha256='offline')
        self.assertEqual(48,len(evidence['samples']))
        for problem in result['problems']:
            self.assertFalse(any(code in problem for code in ('SEQUENCE_', 'ACTION_NOT_IN_CONTINUITY','METADATA_MISMATCH','STATUS_FALLBACK_CONFLICT')),problem)

if __name__ == '__main__':unittest.main()
