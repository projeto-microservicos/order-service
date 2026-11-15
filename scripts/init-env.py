#!/usr/bin/env python3
"""Create development settings once; never print the password."""
from pathlib import Path
import argparse
import os
import secrets
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--update", action="store_true", help="Append missing settings; preserve all existing values")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
text = "\n".join(line.replace("GENERATE_ME", secrets.token_hex(24))
                 for line in (root / ".env.example").read_text().splitlines()) + "\n"
try:
    fd = os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    if not args.update:
        raise SystemExit(".env already exists; kept unchanged. Use --update to append missing settings.")
    current = (root / ".env").read_text()
    existing = {line.split("=", 1)[0].strip() for line in current.splitlines()
                if "=" in line and not line.lstrip().startswith("#")}
    additions = [line for line in text.splitlines()
                 if "=" in line and not line.startswith("#") and line.split("=", 1)[0] not in existing]
    if additions:
        with (root / ".env").open("a") as out:
            out.write(("" if current.endswith("\n") else "\n") + "\n".join(additions) + "\n")
    print(f"Added {len(additions)} missing settings; kept existing values")
    raise SystemExit(0)
with os.fdopen(fd, "w") as out:
    out.write(text)
print("Created development .env")
