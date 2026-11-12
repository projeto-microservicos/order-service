#!/usr/bin/env python3
"""Create development settings once; never print the password."""
from pathlib import Path
import os
import secrets
root = Path(__file__).resolve().parents[1]
text = (root / ".env.example").read_text().replace("GENERATE_ME", secrets.token_hex(24))
try:
    fd = os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit(".env already exists; kept unchanged")
with os.fdopen(fd, "w") as out:
    out.write(text)
print("Created development .env")
