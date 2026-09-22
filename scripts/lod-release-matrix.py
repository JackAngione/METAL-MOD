#!/usr/bin/env python3
"""Run isolated, reproducible Standard/None, 1080p/native, full/half, LOD off/on captures.

Keeps raw reports, logs, screenshots and invocation provenance; a successful run is
measurement completion, never automatic acceptance of a performance or image gate.
Run from the repository root. --resume reuses only matching, qualified captures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def source_digest():
    digest = hashlib.sha256()
    for path in sorted([Path("build.gradle"), *Path("src").rglob("*.java"), *Path("src").rglob("*.metal"),
                        *Path("src").rglob("*.m"), *Path("src").rglob("*.json")]):
        digest.update(str(path).encode()); digest.update(path.read_bytes())
    return digest.hexdigest()


def qualify(report, pack, half, enabled, fullscreen, resolution, repeats):
    if report.get("worldPreset") != "minecraft:normal" or not report.get("chunkLoadAndRenderSettlePassed"):
        raise ValueError("Not a qualified NORMAL-world capture")
    env = report["environment"]
    if report["backend"] != "Metal" or (report["renderDistance"], report["simulationDistance"]) != (16, 16):
        raise ValueError("Expected Default/Metal at 16/16")
    if env["pack"] != ("none" if pack == "none" else "metalcraft-standard"):
        raise ValueError("Wrong shader pack")
    if (env["halfResolution"], env["lodRequested"]["enabled"], env["fullscreen"]) != (half, enabled, fullscreen):
        raise ValueError("Requested settings were not adopted")
    if not fullscreen and (report["drawableWidth"], report["drawableHeight"]) != resolution:
        raise ValueError("Window did not reach the requested drawable size")
    if len(report["phases"]) != repeats * 3:
        raise ValueError("Missing route repeats")
    for phase in report["phases"]:
        presentation = report.get("presentationAfterPhase", {}).get(phase["phase"], {})
        if not presentation.get("passed") or presentation.get("checks", 0) < 60:
            raise ValueError("Missing continuous foreground/visibility checks")
        if presentation["fullscreen"] != fullscreen:
            raise ValueError("Actual monitor attachment differs from requested fullscreen")
        gpu = phase["gpuFrame"]
        if len(gpu["samplesMs"]) < phase["frames"] * .95 or gpu["invalidFrames"] or gpu["overflowFrames"]:
            raise ValueError("GPU frame sample coverage failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/reports/lod-release"))
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--phase-ticks", type=int, default=120)
    parser.add_argument("--repeats", type=int, default=3)
    labels = [f"{resolution}-{pack}-half-{str(half).lower()}-lod-{str(enabled).lower()}"
              for resolution in ("native", "1080p") for pack in ("standard", "none")
              for half in (False, True) for enabled in (False, True)]
    parser.add_argument("--case", action="append", choices=labels,
                        help="Run only this case; repeat for a focused matched pair")
    args = parser.parse_args()
    if args.repeats < 3 or args.phase_ticks < 60:
        parser.error("Release captures require at least three repeats and 60 ticks per phase")
    processes = subprocess.check_output(["ps", "-axo", "args="], text=True)
    if any("net.fabricmc.devlaunchinjector.Main" in line and "java" in line for line in processes.splitlines()):
        raise SystemExit("Another Minecraft client is running; use exclusive GPU access")
    identity = source_digest()
    environment = os.environ.copy()
    if environment.get("MTL_DEBUG_LAYER") not in (None, "0"):
        raise SystemExit("Run performance measurements without Metal API validation; validate separately")
    for label, resolution, fullscreen in (("native", (3840, 2160), True), ("1080p", (1920, 1080), False)):
        for pack in ("standard", "none"):
            for half in (False, True):
                for enabled in (False, True):
                    name = f"{label}-{pack}-half-{str(half).lower()}-lod-{str(enabled).lower()}"
                    if args.case and name not in args.case:
                        continue
                    folder = args.output / name
                    command = ["./gradlew", "runClient", "-PmetalLifecycleTest", "-PmetalLifecycleBenchmark=true",
                               "-PmetalLodTerrainCensus=true", f"-PmetalBenchmarkLod={str(enabled).lower()}",
                               f"-PmetalBenchmarkPack={pack}", "-PmetalBenchmarkRenderDistance=16",
                               "-PmetalBenchmarkSimulationDistance=16", f"-PmetalBenchmarkResolution={resolution[0]}x{resolution[1]}",
                               f"-PmetalBenchmarkFullscreen={str(fullscreen).lower()}",
                               f"-PmetalBenchmarkHalfResolution={str(half).lower()}", "-PmetalBenchmarkUnlocked=true",
                               "-PmetalBenchmarkPitch=30", f"-PmetalBenchmarkRepeats={args.repeats}",
                               f"-PmetalBenchmarkPhaseTicks={args.phase_ticks}", "--args=--graphicsBackend default"]
                    provenance = {"command": command, "sourceSha256": identity}
                    if args.resume and (folder / "invocation.json").is_file() and (folder / "metrics.json").is_file():
                        old = json.loads((folder / "invocation.json").read_text())
                        if old.get("exitCode") == 0 and all(old.get(key) == value for key, value in provenance.items()):
                            qualify(json.loads((folder / "metrics.json").read_text()), pack, half, enabled, fullscreen, resolution, args.repeats)
                            print(f"Reusing {name}", flush=True); continue
                    if source_digest() != identity:
                        raise SystemExit("Sources changed during the matrix; refusing incomparable captures")
                    folder.mkdir(parents=True, exist_ok=True)
                    started = time.time()
                    print(f"Starting {name}", flush=True)
                    with (folder / "client.log").open("w") as log:
                        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment)
                    provenance.update(elapsedSeconds=time.time() - started, exitCode=result.returncode)
                    (folder / "invocation.json").write_text(json.dumps(provenance, indent=2) + "\n")
                    if result.returncode:
                        raise SystemExit(f"{name} failed; see {folder / 'client.log'}")
                    if source_digest() != identity:
                        raise SystemExit("Sources changed during this capture; refusing its provenance")
                    report_path = Path("run/benchmarks/metalcraft-metal.json")
                    if report_path.stat().st_mtime < started:
                        raise SystemExit("Client did not write a fresh benchmark report")
                    scene_path = Path("run/screenshots/0000_metalcraft-world-metal-benchmark.png")
                    if scene_path.stat().st_mtime < started:
                        raise SystemExit("Client did not write a fresh benchmark screenshot")
                    shutil.copy2(report_path, folder / "metrics.json")
                    shutil.copy2(scene_path, folder / "scene.png")
                    qualify(json.loads(report_path.read_text()), pack, half, enabled, fullscreen, resolution, args.repeats)
                    print(f"Qualified {name} ({provenance['elapsedSeconds']:.0f}s)", flush=True)
    print("Matrix capture complete. Review matched comparisons, variability and images before changing release gates.", flush=True)


if __name__ == "__main__":
    main()
