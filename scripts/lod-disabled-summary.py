#!/usr/bin/env python3
"""Compare pre-LOD and current LOD-disabled captures with matched route/presentation controls."""
import json
import importlib.util
from pathlib import Path
import statistics
import sys

spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("lod-release-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


def main(baseline, current):
    cases = []
    baseline_identities, current_identities = set(), set()
    for path in sorted(Path(baseline).glob("*-lod-false/metrics.json")):
        paths = (path, Path(current) / path.parent.name / "metrics.json")
        a, b = [json.loads(p.read_text()) for p in paths]
        invocations = [json.loads(p.with_name("invocation.json").read_text()) for p in paths]
        old, new = invocations
        if (any(i["exitCode"] != 0 for i in invocations) or old["baseCommit"] != "f48460d"
                or old["currentSourceSha256"] != new["sourceSha256"] or old["command"] != new["command"]):
            raise ValueError("Mismatched or unsuccessful baseline provenance")
        if "pairStartedAtUnix" in new and old.get("pairStartedAtUnix") != new["pairStartedAtUnix"]:
            raise ValueError("Disabled captures were not run as one matched pair")
        baseline_identities.add(old["measurementPatchSha256"])
        current_identities.add(new["sourceSha256"])
        for report in (a, b):
            matrix.qualify(report, "none" if "-none-" in path.parent.name else "standard",
                           "half-true" in path.parent.name, False, path.parent.name.startswith("native"),
                           (1920, 1080), 3)
        for key in ("backend", "width", "height", "drawableWidth", "drawableHeight", "seed", "renderDistance", "simulationDistance",
                    "siteX", "siteY", "siteZ", "routeVersion", "requestedPitchDegrees", "cameraSamples", "maxHeapMiB", "gc"):
            if a[key] != b[key]:
                raise ValueError("Baseline route differs: " + key)
        for key in ("device", "osVersion", "javaVersion", "pack", "shaderOptions", "halfResolution", "unlockedFrameRate",
                    "vsync", "frameLimit", "fov", "passMerging", "commandBatching", "fullscreen"):
            if a["environment"][key] != b["environment"][key]:
                raise ValueError("Baseline environment differs: " + key)
        for report in (a,b):
            if report["environment"]["lodRequested"]["enabled"] or not report["chunkLoadAndRenderSettlePassed"]:
                raise ValueError("Not a settled LOD-disabled capture")
        if b["environment"].get("lodTerrainCensus") is False:
            for phase in b["phases"]:
                if any(phase["lod"][edge][key] for edge in ("start", "end")
                       for key in ("uploads", "draws", "chargedBytes", "terrainDraws", "prepareNanos")):
                    raise ValueError("Ordinary disabled path retained LOD work")
            if b["environment"]["lodCapture"]["sections"]:
                raise ValueError("Ordinary disabled path captured terrain meshes")
        phases = {}
        for name in ("stationary", "pan", "traversal"):
            selected = [[p for p in j["phases"] if p["phase"].split("#")[0] == name] for j in (a,b)]
            if any(len(ps) != 3 for ps in selected):
                raise ValueError("Missing three repeats")
            rows = []
            for ps in selected:
                row = {k: statistics.median(p[k] for p in ps) for k in ("p50IntervalMs","p99IntervalMs","p50CpuMs","p99CpuMs")}
                row["gpuP50Ms"] = statistics.median(p["gpuFrame"]["p50Ms"] for p in ps)
                row["medianIntervalRangeMs"] = [min(p["p50IntervalMs"] for p in ps), max(p["p50IntervalMs"] for p in ps)]
                rows.append(row)
            changes = {k: 100*(rows[1][k]/rows[0][k]-1) for k in ("p50IntervalMs","p99IntervalMs","p50CpuMs","p99CpuMs","gpuP50Ms")}
            phases[name] = dict(baseline=rows[0], current=rows[1], changePercent=changes,
                                medianWithin2Percent=changes["p50IntervalMs"]<=2,
                                p99Within5Percent=changes["p99IntervalMs"]<=5)
        cases.append(dict(name=path.parent.name, phases=phases))
    if len(cases) != 8:
        raise ValueError("Expected eight pre-LOD/disabled pairs")
    if len(baseline_identities) != 1 or len(current_identities) != 1:
        raise ValueError("Baseline comparison mixes source revisions")
    print(json.dumps(dict(scope="f48460d renderer with measurement-only backport and shared texel-buffer correctness fix versus current LOD off; median of three repeat percentiles; invocation commands state capability flags",
                          measurementPatchSha256=baseline_identities.pop(), currentSourceSha256=current_identities.pop(),
                          cases=cases), indent=2))


if __name__ == "__main__":
    main(*sys.argv[1:])
