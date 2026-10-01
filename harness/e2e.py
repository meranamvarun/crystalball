#!/usr/bin/env python3
"""Cross-stack E2E: start a real hearth-server (Rust) and run the Kotlin HubE2ETest against it.

Fails if the E2E test was skipped, so a green result always means the two stacks really talked.
"""

import os
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULT = ROOT / "android/core/build/test-results/test/TEST-com.hearth.core.HubE2ETest.xml"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_healthy(url: str, timeout_s: float = 30) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{url}/healthz", timeout=1) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(0.3)
    raise SystemExit(f"hub at {url} did not become healthy")


def main() -> int:
    RESULT.unlink(missing_ok=True)  # never let a stale pass stand in for this run
    subprocess.run(["cargo", "build", "--locked", "-p", "hearth-server"], cwd=ROOT / "backend", check=True)
    port = free_port()
    url = f"http://127.0.0.1:{port}"
    with tempfile.TemporaryDirectory() as tmp:
        hub = subprocess.Popen([str(ROOT / "backend/target/debug/hearth-server"), "--db", f"{tmp}/e2e.db",
                                "--bind", f"127.0.0.1:{port}", "--no-mdns"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            wait_healthy(url)
            env = dict(os.environ, HEARTH_HUB_URL=url, NO_PROXY="127.0.0.1,localhost")
            test = subprocess.run(["./gradlew", "--console=plain", ":core:test",
                                   "--tests", "com.hearth.core.HubE2ETest", "--rerun"],
                                  cwd=ROOT / "android", env=env)
        finally:
            hub.terminate()
            out, _ = hub.communicate(timeout=10)
    if test.returncode != 0:
        print("---- hub log ----\n" + out[-3000:])
        return test.returncode
    xml = RESULT.read_text() if RESULT.exists() else ""
    m = re.search(r'tests="(\d+)" skipped="(\d+)"', xml)
    if not m or int(m.group(1)) == 0 or int(m.group(2)) != 0:
        print(f"E2E test did not run (result: {m.groups() if m else 'missing'})")
        return 1
    print("E2E: Kotlin phones <-> Rust hub OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
