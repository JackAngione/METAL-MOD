#!/usr/bin/env python3
"""Summarize paired explored-patch captures; never infer dense horizon coverage.

Usage: python3 scripts/lod-horizon-summary.py path/to/lod-horizon.json
Each value is the median of three repeat percentiles, not a pooled percentile.
"""
import json
from pathlib import Path
import statistics
import sys


def summarize(rows):
    result = {}
    for field in ("p50CpuMs", "p95CpuMs", "p99CpuMs", "p50IntervalMs", "p99IntervalMs", "onePercentLowFps"):
        result[field] = statistics.median(r["phase"][field] for r in rows)
    for field in ("p50Ms", "p95Ms", "p99Ms"):
        result["gpu" + field[0].upper() + field[1:]] = statistics.median(r["phase"]["gpuFrame"][field] for r in rows)
    result["minimumGpuSampleCoverage"] = min(len(r["phase"]["gpuFrame"]["samplesMs"]) / r["phase"]["frames"] for r in rows)
    result["representedSectionsRange"] = [min(r["distantAfter"]["frameSections"] for r in rows),
                                          max(r["distantAfter"]["frameSections"] for r in rows)]
    result["maxSampledDistantGpuBytes"] = max(r["distantAfter"]["gpuBytes"] for r in rows)
    result["maxSampledDiskBytes"] = max((r["distantAfter"].get("cache") or {}).get("diskBytes", 0) for r in rows)
    diagnostics = [r["distantDiagnostics"] for r in rows]
    result["maxDistantGpuPayloadHighWaterBytes"] = max(d["peakGpuPayloadBytes"] for d in diagnostics)
    result["maxCompressedQueueHighWaterBytes"] = max((d.get("cache") or {}).get("peakQueuedBytes",0) for d in diagnostics)
    result["maxDiskPayloadHighWaterBytes"] = max(((d.get("cache") or {}).get("store") or {}).get("peakDiskPayloadBytes",0) for d in diagnostics)
    result["maxStoreUpdateMs"] = max(((d.get("cache") or {}).get("store") or {}).get("maxUpdateNanos",0) for d in diagnostics) / 1e6
    result["maxProcessPeakPhysicalFootprintBytes"] = max(r["environment"]["processPeakPhysicalFootprintBytes"] for r in rows)
    result["thermalStates"] = [r["environment"]["thermalState"] for r in rows]
    result["p95LoadedPrepareMs"] = statistics.median(r["phase"]["lod"]["p95PrepareMs"] for r in rows)
    result["distantTrianglesPerCapturedFrameIncludingWarmup"] = statistics.median(
        (r["distantAfter"]["triangles"] - r["distantBefore"]["triangles"]) / r["phase"]["frames"] for r in rows)
    result["medianIntervalRangeMs"] = [min(r["phase"]["p50IntervalMs"] for r in rows),
                                       max(r["phase"]["p50IntervalMs"] for r in rows)]
    return result


def main(path):
    report = json.loads(Path(path).read_text())
    output = {"scope": "Paired stationary explored patches, not dense circles; three repeats; median of repeat percentiles",
              "resourceScope": "GPU high-water is successful charged mesh payload including retired resources, not driver allocations; disk high-water includes temporary owned file payload before rename/trim, not filesystem metadata; store latency is completed worker batches, not queue wait",
              "explorationElapsedNanos": report["explorationElapsedNanos"],
              "exploredDiagnostics": report["exploredDiagnostics"],
              "readinessWaitNanos": report["readinessWaitNanos"],
              "repeatedRepairs": report["repeatedRepairs"], "cases": []}
    for horizon in (128, 256):
        rows = report["benchmark" + str(horizon)]
        if len(rows) != 24:
            raise ValueError("Missing horizon matrix captures")
        for pack in ("metalcraft-standard", "none"):
            for half in (False, True):
                selected = [r for r in rows if r["environment"]["pack"] == pack and r["environment"]["halfResolution"] == half]
                a, b = [[r for r in selected if r["enabled"] == enabled] for enabled in (False, True)]
                if len(a) != 3 or len(b) != 3:
                    raise ValueError("Missing off/on repeats")
                for off, on in zip(a, b):
                    for key in ("camera", "drawable", "repeat", "horizon", "trackedChunks", "missingTrackedChunks"):
                        if off[key] != on[key]:
                            raise ValueError("Mismatched horizon pair: " + key)
                    for key in ("pack", "shaderOptions", "halfResolution", "fullscreen", "sceneWidth", "sceneHeight",
                                "renderDistance", "simulationDistance", "device", "vsync", "frameLimit", "fov", "passMerging"):
                        if off["environment"][key] != on["environment"][key]:
                            raise ValueError("Mismatched environment: " + key)
                    for row in (off, on):
                        if not row.get("presentation", {}).get("passed") or row["presentation"].get("checks", 0) < 80:
                            raise ValueError("Missing continuous foreground/visibility checks")
                        gpu = row["phase"]["gpuFrame"]
                        if len(gpu["samplesMs"]) < row["phase"]["frames"] * .95 or gpu["invalidFrames"] or gpu["overflowFrames"]:
                            raise ValueError("Incomplete GPU timing")
                off, on = summarize(a), summarize(b)
                changes = {k: 100 * (on[k] / off[k] - 1) for k in
                           ("p50CpuMs", "p99CpuMs", "p50IntervalMs", "p99IntervalMs", "gpuP50Ms", "gpuP95Ms", "gpuP99Ms")}
                paired = [{k: 100 * (y["phase"][k] / x["phase"][k] - 1)
                           for k in ("p50IntervalMs", "p99IntervalMs")} for x, y in zip(a, b)]
                output["cases"].append(dict(horizon=horizon, pack=pack, halfResolution=half,
                                            drawable=a[0]["drawable"], off=off, on=on, changePercent=changes,
                                            pairedRepeatChangePercent=paired,
                                            withinProvisionalMedianBudget=changes["p50IntervalMs"] <= 25,
                                            withinProvisionalP99Budget=changes["p99IntervalMs"] <= 35))
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
