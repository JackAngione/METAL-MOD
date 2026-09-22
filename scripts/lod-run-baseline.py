#!/usr/bin/env python3
"""Run pre-LOD disabled counterparts, optionally paired with fresh current captures.

Requires an f48460d worktree prepared by lod-prepare-baseline.py. Run from the
current repository, with no other Minecraft client or GPU benchmark running.
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


def qualify(folder, name):
    matrix.qualify(json.loads((folder / "metrics.json").read_text()),
                   "none" if "-none-" in name else "standard", "half-true" in name,
                   False, name.startswith("native"), (1920, 1080), 3)


def reusable(folder, provenance, name):
    if not (folder / "invocation.json").is_file() or not (folder / "metrics.json").is_file():
        return False
    old = json.loads((folder / "invocation.json").read_text())
    if old.get("exitCode") != 0 or any(old.get(k) != v for k, v in provenance.items()):
        return False
    qualify(folder, name)
    return True


def capture(root, folder, provenance, name, label):
    folder.mkdir(parents=True, exist_ok=True)
    if (folder / "invocation.json").exists():
        # Resume can revisit an exhausted retry set or an incomplete pair. Keep its
        # rejected evidence instead of overwriting attempt 1/2 and the final failure.
        previous = folder / ("prior-capture-" + str(time.time_ns()))
        entries = [p for p in folder.iterdir() if not p.name.startswith("prior-capture-")]
        previous.mkdir()
        for entry in entries:
            shutil.move(str(entry), previous / entry.name)
    rejected = []
    for attempt in range(3):
        started = time.time()
        print("Starting " + label + " " + name + f" attempt={attempt+1}", flush=True)
        with (folder / "client.log").open("w") as log:
            result = subprocess.run(provenance["command"], cwd=root, stdout=log, stderr=subprocess.STDOUT)
        recorded = dict(provenance, exitCode=result.returncode, startedAtUnix=started,
                        elapsedSeconds=time.time()-started, rejectedPresentationAttempts=rejected)
        (folder / "invocation.json").write_text(json.dumps(recorded, indent=2) + "\n")
        if not result.returncode:
            break
        log_text = (folder / "client.log").read_text()
        interrupted = ("Benchmark presentation changed:" in log_text
                       or "MetalBenchmarkEnvironment.focus" in log_text and "Timed out waiting for predicate" in log_text)
        if not interrupted or attempt == 2:
            raise SystemExit("Capture failed: " + str(folder))
        archive = folder / f"rejected-presentation-{attempt+1}"
        archive.mkdir(exist_ok=True)
        for filename in ("client.log", "invocation.json"):
            shutil.copy2(folder / filename, archive / filename)
        rejected.append(dict(exitCode=result.returncode, elapsedSeconds=recorded["elapsedSeconds"],
                             reason="Lost or unavailable foreground presentation; entire process capture discarded"))
        print("Discarded interrupted process capture " + label + " " + name, flush=True)
    for source, target in (("benchmarks/metalcraft-metal.json", "metrics.json"),
                           ("screenshots/0000_metalcraft-world-metal-benchmark.png", "scene.png")):
        path = root / "run" / source
        if not path.is_file() or path.stat().st_mtime < started:
            raise SystemExit("Missing fresh artifact: " + str(path))
        shutil.copy2(path, folder / target)
    qualify(folder, name)
    print("Qualified " + label + " " + name, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path)
    parser.add_argument("matrix", type=Path, help="Completed matrix used as the exact command catalog")
    parser.add_argument("output", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--case", action="append", help="Run only this catalog case; repeat to select several")
    parser.add_argument("--paired-current", action="store_true",
                        help="Run both renderers back-to-back with alternating order; save pre-lod/ and current/")
    args = parser.parse_args()
    root = args.worktree.resolve()
    if subprocess.check_output(["git", "-C", str(root), "rev-parse", "--short=7", "HEAD"], text=True).strip() != "f48460d":
        raise SystemExit("Expected the prepared f48460d baseline worktree")
    if os.environ.get("MTL_DEBUG_LAYER") not in (None, "0"):
        raise SystemExit("Run performance captures without Metal API validation")
    processes = subprocess.check_output(["ps", "-axo", "args="], text=True)
    if any("net.fabricmc.devlaunchinjector.Main" in line and "java" in line for line in processes.splitlines()):
        raise SystemExit("Another Minecraft client is running")
    cases = sorted(args.matrix.glob("*-lod-false"), key=lambda p: (not p.name.startswith("native"), p.name))
    if len(cases) != 8 or len(list(args.matrix.glob("*-lod-true/invocation.json"))) != 8:
        raise SystemExit("Expected a completed 16-case matrix")
    if args.case and set(args.case) - {case.name for case in cases}:
        raise SystemExit("Unknown case requested")
    patch = subprocess.check_output(["git", "-C", str(root), "diff"], text=True)
    identity = hashlib.sha256(patch.encode()).hexdigest()
    current_identity = matrix.source_digest()
    current_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "measurement-backport.patch").write_text(patch)
    if args.paired_current:
        # Committing identical source between sessions must not discard good pairs
        # or replace their original base-relative patch with an empty HEAD diff.
        existing = [json.loads(p.read_text()) for p in (args.output / "current").glob("*/invocation.json")]
        matching = [i for i in existing if i.get("sourceSha256") == current_identity]
        bases = {i["baseCommit"] for i in matching}
        source_patch = args.output / "current-source.patch"
        manifest = args.output / "current-provenance.json"
        recorded = json.loads(manifest.read_text()) if manifest.exists() else {}
        if (args.resume and len(bases) == 1 and source_patch.exists()
                and recorded.get("sourceSha256") == current_identity and recorded.get("baseCommit") in bases
                and recorded.get("sourcePatchSha256") == hashlib.sha256(source_patch.read_bytes()).hexdigest()):
            current_commit = bases.pop()
        else:
            if source_patch.exists():
                shutil.copy2(source_patch, args.output / ("prior-source-" + str(time.time_ns()) + ".patch"))
            source_patch.write_text(subprocess.check_output(["git", "diff", "--", "src", "build.gradle"], text=True))
        manifest.write_text(json.dumps(dict(baseCommit=current_commit, sourceSha256=current_identity,
                                           sourcePatchSha256=hashlib.sha256(source_patch.read_bytes()).hexdigest()), indent=2) + "\n")
    for relative in ("options.txt", "config/metalcraft.json"):
        target = root / "run" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path("run") / relative, target)
    for index, case in enumerate(cases):
        if args.case and case.name not in args.case:
            continue
        invocation = json.loads((case / "invocation.json").read_text())
        if invocation["exitCode"] != 0:
            raise SystemExit("Command catalog case failed: " + case.name)
        command = invocation["command"]
        source = invocation["sourceSha256"]
        if args.paired_current:
            # Verify ordinary release availability without a development capability flag.
            command = [arg for arg in command if arg not in ("-PmetalLodExperimental=true", "-PmetalLodTerrainCensus=true")]
            source = current_identity
        baseline = dict(command=command, baseCommit="f48460d", measurementPatchSha256=identity,
                        currentSourceSha256=source)
        baseline_folder = args.output / ("pre-lod" if args.paired_current else "") / case.name
        captures = [(root, baseline_folder, baseline, "pre-LOD")]
        if args.paired_current:
            current = dict(command=command, baseCommit=current_commit, sourceSha256=source)
            captures.append((Path.cwd(), args.output / "current" / case.name, current, "current"))
            if index % 2:
                captures.reverse()
        if args.resume and all(reusable(folder, provenance, case.name) for _, folder, provenance, _ in captures):
            if not args.paired_current or len({json.loads((folder / "invocation.json").read_text()).get("pairStartedAtUnix")
                                              for _, folder, _, _ in captures}) == 1:
                print("Reusing complete comparison " + case.name, flush=True)
                continue
        pair_started = time.time()
        for working, folder, provenance, label in captures:
            if matrix.source_digest() != current_identity or subprocess.check_output(["git", "-C", str(root), "diff"], text=True) != patch:
                raise SystemExit("Sources changed during disabled comparison")
            capture(working, folder, dict(provenance, pairStartedAtUnix=pair_started), case.name, label)
            if matrix.source_digest() != current_identity or subprocess.check_output(["git", "-C", str(root), "diff"], text=True) != patch:
                raise SystemExit("Sources changed during disabled capture")
    print("Disabled comparison captures complete", flush=True)


if __name__ == "__main__":
    main()
