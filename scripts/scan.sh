#!/usr/bin/env bash
# Run after mvn verify. Scan the packaged runtime dependencies, including nested JARs.
set -Eeuo pipefail
mkdir -p artifacts
if ! compgen -G 'target/*.jar' >/dev/null; then
  printf 'Run mvn -B -ntp verify before scanning.\n' >&2
  exit 1
fi
scan=(rootfs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --list-all-pkgs --no-progress --timeout 15m)
# rootfs enables Java archive analysis; the fs mode only analyzes pre-build manifests.
: > artifacts/vulnerabilities.json
set +e
if command -v trivy >/dev/null; then
  TRIVY_CACHE_DIR="${TRIVY_CACHE_DIR:-$PWD/artifacts/.trivy-cache}" \
    trivy "${scan[@]}" --output artifacts/vulnerabilities.json target
else
  docker run --rm --user "$(id -u):$(id -g)" \
    -e TRIVY_CACHE_DIR=/reports/.trivy-cache \
    -v "$PWD/target:/scan:ro" -v "$PWD/artifacts:/reports" \
    aquasec/trivy:0.76.2 "${scan[@]}" --output /reports/vulnerabilities.json /scan
fi
scan_status=$?
set -e
# Fail closed if analysis accidentally scanned no runtime libraries.
python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path('artifacts/vulnerabilities.json').read_text())
packages = [package for result in report.get('Results', []) if result.get('Type') == 'jar'
            for package in result.get('Packages', [])]
if not packages:
    raise SystemExit('Scan did not analyze any Java runtime libraries')
print(f'Analyzed {len(packages)} Java runtime libraries; report: artifacts/vulnerabilities.json')
PY
exit "$scan_status"
