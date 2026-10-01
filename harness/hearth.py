#!/usr/bin/env python3
"""Hearth self-correcting harness. See harness/README.md."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearth_harness.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
