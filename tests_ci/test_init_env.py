"""Development settings upgrades preserve existing values and keep secrets distinct."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


class InitEnvironmentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(REPO / 'scripts/init-env.py', self.root / 'scripts/init-env.py')
        shutil.copy(REPO / '.env.example', self.root / '.env.example')

    def run_init(self, *args):
        return subprocess.run([sys.executable, str(self.root / 'scripts/init-env.py'), *args],
                              text=True, capture_output=True)

    def test_fresh_settings_have_distinct_unlogged_passwords(self):
        result = self.run_init()
        self.assertEqual(result.returncode, 0, result.stderr)
        settings = dict(line.split('=', 1) for line in (self.root / '.env').read_text().splitlines()
                        if '=' in line and not line.startswith('#'))
        passwords = [settings[name] for name in ('DB_PASSWORD', 'RABBITMQ_PASSWORD', 'GRAFANA_PASSWORD')]
        self.assertEqual(len(set(passwords)), 3)
        for password in passwords:
            self.assertEqual(len(password), 48)
            self.assertNotIn(password, result.stdout + result.stderr)

    def test_update_preserves_existing_settings_and_is_repeatable(self):
        existing = 'DB_PASSWORD=keep-this-secret\nHTTP_PORT=8802\nCUSTOM_VALUE=preserve\n'
        (self.root / '.env').write_text(existing)
        self.assertNotEqual(self.run_init().returncode, 0)
        self.assertEqual((self.root / '.env').read_text(), existing)
        self.assertEqual(self.run_init('--update').returncode, 0)
        updated = (self.root / '.env').read_text()
        self.assertTrue(updated.startswith(existing))
        self.assertIn('RABBITMQ_PASSWORD=', updated)
        self.assertEqual(self.run_init('--update').returncode, 0)
        self.assertEqual((self.root / '.env').read_text(), updated)
