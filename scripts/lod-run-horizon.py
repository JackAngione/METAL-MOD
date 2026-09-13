#!/usr/bin/env python3
"""Capture the paired 128/256-chunk NORMAL-world horizon route and repair diagnostics."""
import argparse
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
    parser.add_argument("--output", type=Path, default=Path("build/reports/lod-horizon-confirmed"))
    parser.add_argument("--cold-cache", action="store_true", help="Remove only the isolated gametest cache before launching")
    parser.add_argument("--cost-probe", action="store_true", help="Measure Standard/full only, with all lifecycle and repair checks")
    args = parser.parse_args()
    processes = subprocess.check_output(["ps", "-axo", "args="], text=True)
    if any("net.fabricmc.devlaunchinjector.Main" in line and "java" in line for line in processes.splitlines()):
        raise SystemExit("Another Minecraft client is running")
    if os.environ.get("MTL_DEBUG_LAYER") not in (None,"0"):
        raise SystemExit("Run performance captures without Metal API validation")
    if args.cold_cache:
        cache = Path("run/build/lod-test-cache")
        if cache.is_symlink():
            raise SystemExit("Refusing to clear a symlinked test cache")
        if cache.exists():
            shutil.rmtree(cache)
    folder = args.output
    folder.mkdir(parents=True, exist_ok=True)
    identity = matrix.source_digest()
    base_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    (folder / "source.patch").write_text(subprocess.check_output(["git","diff","--","src","build.gradle"],text=True))
    command = ["./gradlew","runClient","-PmetalLifecycleTest","-PmetalLodHorizonTest=true",
               "-PmetalLodHorizonBenchmark=true","-PmetalBenchmarkFullscreen=true",
               "-PmetalBenchmarkUnlocked=true","-PmetalBenchmarkHalfResolution=false","--args=--graphicsBackend default"]
    if args.cost_probe:
        command.insert(-1, "-PmetalLodHorizonCostProbe=true")
    started = time.time()
    print("Starting paired horizon capture with repair/resource diagnostics",flush=True)
    with (folder / "client.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    provenance = dict(command=command, baseCommit=base_commit, sourceSha256=identity, coldTestCache=args.cold_cache,
                      exitCode=result.returncode, elapsedSeconds=time.time()-started)
    (folder / "invocation.json").write_text(json.dumps(provenance,indent=2)+"\n")
    report = Path("run/build/lod-horizon.json")
    if report.is_file() and report.stat().st_mtime >= started:
        shutil.copy2(report, folder / "lod-horizon.json")
    images = []
    for path in Path("run/screenshots").glob("*.png"):
        if path.stat().st_mtime >= started:
            shutil.copy2(path,folder/path.name)
            images.append(path.name)
    (folder / "images.json").write_text(json.dumps(sorted(images),indent=2)+"\n")
    if result.returncode or matrix.source_digest() != identity:
        raise SystemExit("Horizon capture failed or sources changed; see saved invocation and log")
    if not (folder / "lod-horizon.json").is_file():
        raise SystemExit("Missing fresh horizon report")
    summary = subprocess.check_output(["python3","scripts/lod-horizon-summary.py",str(folder/"lod-horizon.json")],text=True)
    (folder / "summary.json").write_text(summary)
    print("Horizon capture and lifecycle qualified",flush=True)


if __name__ == "__main__":
    main()
