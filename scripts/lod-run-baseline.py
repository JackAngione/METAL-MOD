#!/usr/bin/env python3
"""Run the eight disabled baseline counterparts of a completed release matrix.

Requires an f48460d worktree prepared by lod-prepare-baseline.py. Uses the exact
same commands and saved renderer preferences; retains raw reports and provenance.
Run from the current repository, with no other Minecraft client or matrix running.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("lod-release-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path)
    parser.add_argument("matrix", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    root = args.worktree.resolve()
    if subprocess.check_output(["git", "-C", str(root), "rev-parse", "--short=7", "HEAD"], text=True).strip() != "f48460d":
        raise SystemExit("Expected the prepared f48460d baseline worktree")
    if os.environ.get("MTL_DEBUG_LAYER") not in (None, "0"):
        raise SystemExit("Run performance captures without Metal API validation")
    processes = subprocess.check_output(["ps", "-axo", "args="], text=True)
    if any("net.fabricmc.devlaunchinjector.Main" in line and "java" in line for line in processes.splitlines()):
        raise SystemExit("Another Minecraft client is running")
    cases = sorted(args.matrix.glob("*-lod-false"))
    if len(cases) != 8 or len(list(args.matrix.glob("*-lod-true/invocation.json"))) != 8:
        raise SystemExit("Expected a completed 16-case matrix")
    patch = subprocess.check_output(["git", "-C", str(root), "diff"], text=True)
    identity = hashlib.sha256(patch.encode()).hexdigest()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "measurement-backport.patch").write_text(patch)
    for relative in ("options.txt", "config/metalcraft.json"):
        target = root / "run" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path("run") / relative, target)
    for case in cases:
        invocation = json.loads((case / "invocation.json").read_text())
        if invocation["exitCode"] != 0:
            raise SystemExit("Current comparison failed: " + case.name)
        folder = args.output / case.name
        folder.mkdir(parents=True, exist_ok=True)
        command = invocation["command"]
        if args.resume and (folder / "invocation.json").is_file() and (folder / "metrics.json").is_file():
            old = json.loads((folder / "invocation.json").read_text())
            if (old.get("exitCode") == 0 and old.get("command") == command
                    and old.get("measurementPatchSha256") == identity
                    and old.get("currentSourceSha256") == invocation["sourceSha256"]):
                matrix.qualify(json.loads((folder / "metrics.json").read_text()),
                               "none" if "-none-" in case.name else "standard", "half-true" in case.name,
                               False, case.name.startswith("native"), (1920,1080), 3)
                print("Reusing pre-LOD " + case.name, flush=True)
                continue
        start = time.time()
        print("Starting pre-LOD " + case.name, flush=True)
        with (folder / "client.log").open("w") as log:
            result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        provenance = dict(command=command, baseCommit="f48460d", measurementPatchSha256=identity,
                          currentSourceSha256=invocation["sourceSha256"], exitCode=result.returncode, elapsedSeconds=time.time()-start)
        (folder / "invocation.json").write_text(json.dumps(provenance, indent=2) + "\n")
        if result.returncode:
            raise SystemExit("Pre-LOD capture failed: " + str(folder))
        if subprocess.check_output(["git", "-C", str(root), "diff"], text=True) != patch:
            raise SystemExit("Baseline sources changed during capture")
        for source, target in (("benchmarks/metalcraft-metal.json", "metrics.json"),
                               ("screenshots/0000_metalcraft-world-metal-benchmark.png", "scene.png")):
            path = root / "run" / source
            if path.stat().st_mtime < start:
                raise SystemExit("Missing fresh baseline artifact")
            shutil.copy2(path, folder / target)
        report = json.loads((folder / "metrics.json").read_text())
        matrix.qualify(report, "none" if "-none-" in case.name else "standard", "half-true" in case.name,
                       False, case.name.startswith("native"), (1920,1080), 3)
        print("Qualified pre-LOD " + case.name, flush=True)


if __name__ == "__main__":
    main()
