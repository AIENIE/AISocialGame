import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from closure_identity import prepare, artifact, identity

class ClosureIdentityTest(unittest.TestCase):
    def test_prompt_text_and_review_rubric_changes_invalidate_old_build(self):
        for name in ('backend/src/main/resources/game-knowledge/social-player-system.txt', 'doc/ai-realism-manual-review-rubric.md'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root=Path(directory);source=root/name;source.parent.mkdir(parents=True);source.write_text('original',encoding='utf-8')
                value=prepare(root);jar=root/'backend/target/test.jar'
                with zipfile.ZipFile(jar,'w') as bundle:bundle.writestr('BOOT-INF/classes/closure-build.json',json.dumps(value))
                artifact(root,jar)
                source.write_text('changed prompt or rubric',encoding='utf-8')
                self.assertNotEqual(value,identity(root))
                with self.assertRaisesRegex(ValueError,'STALE_BUILD_IDENTITY'):artifact(root,jar,True)
    def test_build_resource_and_jar_are_bound_to_source_and_evaluator(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'backend/src/main/java/Test.java';source.parent.mkdir(parents=True);source.write_text('class Test {}')
            script=root/'scripts/windows/support/closure_metrics.py';script.parent.mkdir(parents=True);script.write_text('original')
            value=prepare(root);jar=root/'backend/target/test.jar'
            with zipfile.ZipFile(jar,'w') as bundle:bundle.writestr('BOOT-INF/classes/closure-build.json',json.dumps(value))
            result=artifact(root,jar);self.assertEqual(value['buildId'],result['buildId']);self.assertEqual(result,artifact(root,jar,True))
            script.write_text('changed evaluator');self.assertNotEqual(value,identity(root))
            with self.assertRaises(ValueError):artifact(root,jar,True)
    def test_changed_artifact_rejected_even_if_embedded_identity_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);value=prepare(root);jar=root/'backend/target/test.jar'
            with zipfile.ZipFile(jar,'w') as bundle:bundle.writestr('BOOT-INF/classes/closure-build.json',json.dumps(value))
            artifact(root,jar)
            with zipfile.ZipFile(jar,'a') as bundle:bundle.writestr('unexpected.txt','changed')
            with self.assertRaises(ValueError):artifact(root,jar,True)
if __name__=='__main__':unittest.main()
