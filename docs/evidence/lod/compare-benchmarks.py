"""Compare explicit LOD-off/on reports; report measurements without promoting release gates.

Usage: python3 compare-benchmarks.py OFF/metrics.json ON/metrics.json
Output is JSON. Phase percentiles are summarized by their median across repeats,
not represented as pooled frame percentiles. GPU spans overlap and are not additive.
"""
import json
import statistics
import sys
from pathlib import Path

off, on = [json.load(open(path)) for path in sys.argv[1:]]


def settle(data, path):
    if "chunkLoadAndRenderSettlePassed" in data:
        return data["chunkLoadAndRenderSettlePassed"]
    # Legacy captures did not serialize the combined Fabric wait result. A timeout
    # in the preserved log establishes failure; absence of that line proves nothing.
    log = Path(path).with_name("client.log")
    if log.is_file() and "chunk meshing did not settle" in log.read_text():
        return False
    return None
for key in ("backend", "width", "height", "drawableWidth", "drawableHeight", "seed",
            "renderDistance", "simulationDistance", "siteX", "siteY", "siteZ", "routeVersion",
            "requestedPitchDegrees", "cameraSamples", "maxHeapMiB", "gc"):
    if off[key] != on[key]:
        raise ValueError(f"A/B contract differs: {key}")
for key in ("device", "osVersion", "javaVersion", "pack", "halfResolution", "unlockedFrameRate",
            "vsync", "frameLimit", "fov", "passMerging", "commandBatching"):
    if off["environment"][key] != on["environment"][key]:
        raise ValueError(f"A/B environment differs: {key}")
for key in ("fullscreen", "shaderOptions"):
    if off["environment"].get(key) != on["environment"].get(key):
        raise ValueError(f"A/B environment differs: {key}")
assert not off["environment"]["lodRequested"]["enabled"]
assert on["environment"]["lodRequested"]["enabled"]
for key, value in off["environment"]["lodRequested"].items():
    if key != "enabled" and on["environment"]["lodRequested"][key] != value:
        raise ValueError(f"LOD settings differ: {key}")


def reduction(phase, prefix):
    lod = phase["lod"]
    before = lod["end"][prefix + "InputTriangles"] - lod["start"][prefix + "InputTriangles"]
    after = lod["end"][prefix + "RenderedTriangles"] - lod["start"][prefix + "RenderedTriangles"]
    return 100 * (1 - after / before) if before else 0


def summarize(phases):
    result = {key: statistics.median(p[key] for p in phases) for key in (
        "averageFps", "onePercentLowFps", "p50CpuMs", "p95CpuMs", "p99CpuMs", "p50IntervalMs", "p99IntervalMs")}
    result["fpsRange"] = [min(p["averageFps"] for p in phases), max(p["averageFps"] for p in phases)]
    result["meanAcquireMs"] = statistics.median(
        sum(s["totalMs"] for s in p["phaseStalls"] if s["source"] == "acquire") / p["frames"] for p in phases)
    result["approximately120HzCadence"] = (8.1 <= result["p50IntervalMs"] <= 8.55
                                           and result["meanAcquireMs"] > .8 and result["p50CpuMs"] < 6.8)
    result["gpuCommandBufferBusyMeanMs"] = statistics.median(
        next(s["totalMs"] / s["count"] for s in p["phaseStalls"] if s["source"] == "gpu_frame") for p in phases)
    if all("gpuFrame" in p for p in phases):
        for key in ("p50Ms", "p95Ms", "p99Ms"):
            values = [p["gpuFrame"].get(key) for p in phases]
            result["gpuFrame" + key[0].upper() + key[1:]] = statistics.median(values) if all(v is not None for v in values) else None
        result["minimumGpuSampleCoverage"] = min(len(p["gpuFrame"]["samplesMs"]) / p["frames"] for p in phases)
        result["excludedGpuFrames"] = sum(sum(p["gpuFrame"][k] for k in ("pendingFrames", "invalidFrames", "emptyFrames", "overflowFrames")) for p in phases)
    for key in ("p50PrepareMs", "p95PrepareMs", "p99PrepareMs"):
        result[key] = statistics.median(p["lod"][key] for p in phases)
    for prefix in ("terrain", "distant"):
        result[prefix + "TriangleReductionPercent"] = statistics.median(reduction(p, prefix) for p in phases)
        result[prefix + "InputTrianglesPerFrame"] = statistics.median(
            (p["lod"]["end"][prefix + "InputTriangles"] - p["lod"]["start"][prefix + "InputTriangles"]) / p["frames"]
            for p in phases)
    result["nativeCommandBatchesPerFrame"] = statistics.median(
        next((s["count"] / p["frames"] for s in p["phaseStalls"] if s["source"] == "command_batch"), 0)
        for p in phases)
    for label in ("Section layers for opaque", "MetalCraft shader: shadow_terrain"):
        values = [s["totalMs"] / s["count"] for p in phases for s in p["gpuPassSpans"] if s["pass"] == label]
        result[label + "MeanSpanMs"] = statistics.median(values) if values else None
    result["uploadBytes"] = sum(p["lod"]["end"]["uploadedBytes"]-p["lod"]["start"]["uploadedBytes"] for p in phases)
    result["uploadFailures"] = sum(p["lod"]["end"]["uploadFailures"]-p["lod"]["start"]["uploadFailures"] for p in phases)
    return result


report = {
    "resolution": [off["width"], off["height"]],
    "device": off["environment"]["device"],
    "scope": "One isolated off/on pair, three route repeats each; synthetic GPU/frame percentiles are not inferred",
    "percentileAggregation": "Median of each repeat's percentile; not a pooled percentile",
    "gpuCaveat": "Command-buffer busy means and overlapping pass spans are not exclusive costs or frame percentiles",
    "presentationCaveat": "Requested unlocked/VSync-off settings do not prove unpaced presentation. The 120Hz flag is an interval/acquire heuristic; inspect CPU work, acquire waits and repeat variability separately.",
    "chunkLoadAndRenderSettlePassed": {label: settle(data, path) for label, data, path in
                                      zip(("off", "on"), (off, on), sys.argv[1:])},
    "phases": {},
    "coverage": {label: {k: data[k] for k in ("loadedChunkFraction", "visibleSections", "flatFrameFraction")}
                 for label, data in (("off", off), ("on", on))},
}
for name in ("stationary", "pan", "traversal"):
    summaries = [summarize([p for p in j["phases"] if p["phase"].split("#")[0] == name]) for j in (off, on)]
    a, b = summaries
    change = {k: 100 * (b[k] / a[k] - 1) for k in ("p50CpuMs", "p99CpuMs", "p50IntervalMs", "p99IntervalMs")}
    for key in ("gpuFrameP50Ms", "gpuFrameP95Ms", "gpuFrameP99Ms"):
        if a.get(key) and b.get(key): change[key] = 100 * (b[key] / a[key] - 1)
    report["phases"][name] = {"off": a, "on": b, "changePercent": change}
report["sampledMemory"] = {}
for label, data in (("off", off), ("on", on)):
    samples = list(data["memoryAfterPhase"].values())
    report["sampledMemory"][label] = {
        "maxSampledHeapBytes": max(p["heapUsedBytes"] for p in samples),
        "maxSampledMetalBytes": max(p["metalAllocatedBytes"] for p in samples),
        "maxSampledLodGpuBytes": max(p["lodRenderer"]["chargedBytes"] for p in samples),
        "maxSampledLodRetainedCpuBytes": max(p["lodCapture"]["retainedBytes"] for p in samples),
    }
    for key in ("processPeakResidentBytes", "processPeakPhysicalFootprintBytes"):
        values = [p[key] for p in samples if p.get(key, -1) >= 0]
        report["sampledMemory"][label][key] = max(values) if values else None
    report["sampledMemory"][label]["thermalStates"] = [p.get("thermalState") for p in samples]
    captures = [p["lodCapture"] for p in samples]
    for key in ("maxBuildNanos", "peakReservedBytes", "peakRetainedBytes"):
        values = [p[key] for p in captures if key in p]
        report["sampledMemory"][label][key] = max(values) if values else None
report["gpuFrameContract"] = off.get("gpuFrameContract")
print(json.dumps(report, indent=2))
