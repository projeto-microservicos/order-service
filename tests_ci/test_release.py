"""Offline failure-path tests: no AWS account, Docker daemon or cluster is contacted."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SERVICE = 'order'
REAL_KUBECTL = shutil.which('kubectl')
FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
tool, args = pathlib.Path(sys.argv[0]).name, sys.argv[1:]
with open(os.environ['CALL_LOG'], 'a') as stream:
    stream.write(json.dumps([tool, args]) + '\n')
failure = os.environ.get('FAILURE', '')
def stop():
    print('Simulated command failure', file=sys.stderr)
    sys.exit(17)
if tool == 'git':
    print('a' * 40)
elif tool == 'aws':
    command = ' '.join(args[:2])
    if command == 'sts get-caller-identity':
        print('999999999999' if failure == 'account' else '123456789012')
    elif command == 'ecr describe-repositories':
        if failure == 'repository': stop()
        print('123456789012.dkr.ecr.us-east-1.amazonaws.com/' + os.environ['ECR_REPOSITORY'])
    elif command == 'ecr get-login-password':
        if failure == 'login': stop()
        print('DO_NOT_LOG_THIS_TOKEN')
    elif command == 'ecr describe-images':
        print('invalid' if failure == 'digest' else 'sha256:' + 'b' * 64)
    elif command == 'eks update-kubeconfig':
        if failure == 'kubeconfig': stop()
        pathlib.Path(os.environ['KUBECONFIG']).write_text('temporary')
elif tool == 'docker':
    if args[0] == 'login':
        sys.stdin.read()
        pathlib.Path(os.environ['DOCKER_CONFIG'], 'config.json').write_text('DO_NOT_LOG_THIS_TOKEN')
    if failure == args[0]: stop()
elif tool == 'kubectl':
    if args[0] == 'kustomize':
        config = json.loads(pathlib.Path(args[1], 'kustomization.yaml').read_text())
        pathlib.Path('render-input.json').write_text(json.dumps(config))
        if os.environ.get('REAL_KUBECTL'):
            __import__('subprocess').run([os.environ['REAL_KUBECTL'], 'kustomize', args[1]], check=True)
        else:
            print(json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': []}))
    elif 'get' in args and 'secret' in args and failure == 'secret': stop()
    elif 'apply' in args:
        if '--dry-run=server' in args and failure == 'dry-run': stop()
        if '--dry-run=server' not in args and failure == 'apply': stop()
    elif 'rollout' in args and failure == 'rollout': stop()
'''


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'bin').mkdir()
        (self.root / 'tmp').mkdir()
        (self.root / 'k8s').mkdir()
        shutil.copy(REPO / 'k8s/k8s.yaml', self.root / 'k8s/k8s.yaml')
        for tool in ('aws', 'docker', 'git', 'kubectl'):
            executable = self.root / 'bin' / tool
            executable.write_text(FAKE)
            executable.chmod(0o755)
        self.log = self.root / 'calls.jsonl'
        self.environment = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'],
                                CALL_LOG=str(self.log), TMPDIR=str(self.root / 'tmp'),
                                SERVICE=SERVICE, AWS_REGION='us-east-1', AWS_ACCOUNT_ID='123456789012',
                                ECR_REPOSITORY=SERVICE + '-service', BUILD_NUMBER='42',
                                EKS_CLUSTER_NAME='test-cluster', K8S_NAMESPACE='store',
                                IMAGE_PLATFORM='linux/amd64', DEPLOY='true', FAILURE='')

    def run_release(self, failure='', **changes):
        self.environment.update(changes, FAILURE=failure)
        result = subprocess.run(['bash', str(REPO / 'scripts/release-eks.sh')], cwd=self.root,
                                env=self.environment, text=True, capture_output=True)
        self.calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        self.assertNotIn('DO_NOT_LOG_THIS_TOKEN', result.stdout + result.stderr)
        self.assertEqual(list((self.root / 'tmp').iterdir()), [], 'Temporary credentials must be removed')
        return result

    def called(self, tool, token):
        return any(name == tool and token in args for name, args in self.calls)

    def live_apply(self):
        return any(name == 'kubectl' and 'apply' in args and '--dry-run=server' not in args for name, args in self.calls)

    def test_publish_only_never_connects_to_cluster(self):
        result = self.run_release(DEPLOY='false', EKS_CLUSTER_NAME='')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.called('docker', 'push'))
        self.assertFalse(self.called('aws', 'update-kubeconfig'))
        self.assertFalse(any(tool == 'kubectl' for tool, _ in self.calls))
        release = json.loads((self.root / 'artifacts/release.json').read_text())
        self.assertEqual(release['deployment'], 'not_requested')

    def test_successful_deploy_uses_digest_and_only_application_resources(self):
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.live_apply())
        self.assertTrue(self.called('kubectl', 'rollout'))
        config = json.loads((self.root / 'render-input.json').read_text())
        self.assertEqual(config['images'][0]['digest'], 'sha256:' + 'b' * 64)
        self.assertEqual(config['namespace'], 'store')
        self.assertEqual(config['resources'], ['application.yaml'])
        release = json.loads((self.root / 'artifacts/release.json').read_text())
        self.assertEqual(release['deployment'], 'succeeded')
        calls = [tool + ' ' + ' '.join(args) for tool, args in self.calls]
        self.assertLess(next(i for i, value in enumerate(calls) if value.startswith('docker push')),
                        next(i for i, value in enumerate(calls) if ' rollout ' in value))

    @unittest.skipUnless(REAL_KUBECTL, 'kubectl not installed; real render is checked on the Jenkins agent')
    def test_real_kustomize_render(self):
        result = self.run_release(REAL_KUBECTL=REAL_KUBECTL)
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = (self.root / 'artifacts/deployment.yaml').read_text()
        self.assertIn('kind: Deployment', manifest)
        self.assertIn('namespace: store', manifest)
        self.assertIn(f'image: 123456789012.dkr.ecr.us-east-1.amazonaws.com/{SERVICE}-service@sha256:' + 'b' * 64, manifest)
        self.assertNotIn('kind: Secret', manifest)
        self.assertNotIn('kind: ConfigMap', manifest)

    def test_arm64_selects_compatible_nodes(self):
        result = self.run_release(IMAGE_PLATFORM='linux/arm64')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.called('docker', 'linux/arm64'))
        config = json.loads((self.root / 'render-input.json').read_text())
        patch = json.loads(config['patches'][0]['patch'])
        self.assertEqual(patch[0]['value']['kubernetes.io/arch'], 'arm64')

    def test_wrong_account_stops_before_build(self):
        self.assertNotEqual(self.run_release('account').returncode, 0)
        self.assertFalse(self.called('docker', 'build'))

    def test_missing_repository_stops_before_build(self):
        self.assertNotEqual(self.run_release('repository').returncode, 0)
        self.assertFalse(self.called('docker', 'build'))

    def test_missing_secret_stops_before_build(self):
        self.assertNotEqual(self.run_release('secret').returncode, 0)
        self.assertFalse(self.called('docker', 'build'))

    def test_failed_kubeconfig_stops_before_build(self):
        self.assertNotEqual(self.run_release('kubeconfig').returncode, 0)
        self.assertFalse(self.called('docker', 'build'))

    def test_failed_build_cannot_push_or_deploy(self):
        self.assertNotEqual(self.run_release('build').returncode, 0)
        self.assertFalse(self.called('docker', 'push'))
        self.assertFalse(self.live_apply())

    def test_failed_login_cannot_push_or_deploy(self):
        self.assertNotEqual(self.run_release('login').returncode, 0)
        self.assertFalse(self.called('docker', 'push'))
        self.assertFalse(self.live_apply())

    def test_failed_push_cannot_deploy(self):
        self.assertNotEqual(self.run_release('push').returncode, 0)
        self.assertFalse(self.live_apply())

    def test_bad_digest_cannot_deploy(self):
        self.assertNotEqual(self.run_release('digest').returncode, 0)
        self.assertFalse(self.live_apply())

    def test_server_validation_failure_cannot_apply(self):
        self.assertNotEqual(self.run_release('dry-run').returncode, 0)
        self.assertFalse(self.live_apply())

    def test_apply_failure_cannot_report_success(self):
        self.assertNotEqual(self.run_release('apply').returncode, 0)
        self.assertFalse(self.called('kubectl', 'rollout'))

    def test_rollout_failure_fails_release(self):
        self.assertNotEqual(self.run_release('rollout').returncode, 0)
        release = json.loads((self.root / 'artifacts/release.json').read_text())
        self.assertNotEqual(release['deployment'], 'succeeded')

    def test_invalid_namespace_has_no_external_effects(self):
        self.assertNotEqual(self.run_release(K8S_NAMESPACE='store; echo bad').returncode, 0)
        self.assertEqual(self.calls, [])

    def test_invalid_deploy_flag_has_no_external_effects(self):
        self.assertNotEqual(self.run_release(DEPLOY='yes').returncode, 0)
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
