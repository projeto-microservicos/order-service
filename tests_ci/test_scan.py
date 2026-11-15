"""Check that the vulnerability gate cannot silently pass without Java analysis."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
FAKE = '''#!/usr/bin/env python3
import json, os, pathlib, sys
pathlib.Path('invocation.json').write_text(json.dumps(sys.argv[1:]))
pathlib.Path('artifacts/vulnerabilities.json').write_text(json.dumps({
    'Results': [] if os.environ.get('EMPTY_SCAN') else [
        {'Type': 'jar', 'Packages': [{'Name': 'example', 'Version': '1.0'}]}]}))
sys.exit(int(os.environ.get('SCAN_EXIT', '0')))
'''


class ScanTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'target').mkdir()
        (self.root / 'target/application.jar').touch()
        (self.root / 'bin').mkdir()
        executable = self.root / 'bin/trivy'
        executable.write_text(FAKE)
        executable.chmod(0o755)

    def scan(self, **changes):
        environment = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'], **changes)
        return subprocess.run(['bash', str(REPO / 'scripts/scan.sh')], cwd=self.root,
                              env=environment, text=True, capture_output=True)

    def test_runtime_analysis_succeeds_with_real_package_inventory(self):
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'invocation.json').read_text())
        self.assertEqual(args[0], 'rootfs')
        self.assertIn('--list-all-pkgs', args)

    def test_empty_analysis_cannot_pass(self):
        self.assertNotEqual(self.scan(EMPTY_SCAN='1').returncode, 0)

    def test_vulnerability_and_tool_failures_block_release(self):
        for status in ('1', '2'):
            with self.subTest(status=status):
                self.assertEqual(self.scan(SCAN_EXIT=status).returncode, int(status))

    def test_missing_jar_stops_before_scanner(self):
        (self.root / 'target/application.jar').unlink()
        self.assertNotEqual(self.scan().returncode, 0)
        self.assertFalse((self.root / 'invocation.json').exists())
