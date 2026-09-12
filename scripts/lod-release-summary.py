#!/usr/bin/env python3
"""Qualify and compare a completed 16-case release matrix; output JSON, not a release decision."""
import json
from pathlib import Path
import subprocess
import sys


def main(root):
    root = Path(root)
    cases = []
    identities = set()
    for resolution in ("1080p", "native"):
        for pack in ("standard", "none"):
            for half in (False, True):
                name = f"{resolution}-{pack}-half-{str(half).lower()}"
                folders = [root / (name + f"-lod-{str(enabled).lower()}") for enabled in (False, True)]
                for folder in folders:
                    invocation = json.loads((folder / "invocation.json").read_text())
                    if invocation["exitCode"] != 0:
                        raise ValueError("Failed matrix case: " + str(folder))
                    identities.add(invocation["sourceSha256"])
                comparison = json.loads(subprocess.check_output([
                    sys.executable, "docs/evidence/lod/compare-benchmarks.py",
                    *[str(folder / "metrics.json") for folder in folders]], text=True))
                if not all(comparison["chunkLoadAndRenderSettlePassed"].values()):
                    raise ValueError("Unqualified terrain readiness: " + name)
                for phase in comparison["phases"].values():
                    for enabled in ("off", "on"):
                        if phase[enabled]["minimumGpuSampleCoverage"] < .95 or phase[enabled]["uploadFailures"]:
                            raise ValueError("Incomplete timing or upload failure: " + name)
                cases.append(dict(name=name, **comparison))
    if len(identities) != 1:
        raise ValueError("Matrix mixes source revisions")
    print(json.dumps(dict(sourceSha256=identities.pop(), cases=cases), indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
