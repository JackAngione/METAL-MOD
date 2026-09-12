#!/usr/bin/env python3
"""Backport the current measurement harness to an existing f48460d pre-LOD worktree.

Run once on a clean detached worktree. Renderer changes are limited to measurement
probes and the shared texel-buffer validation correction. No LOD hooks are installed.
The resulting full patch is the auditable baseline provenance.
"""
import argparse
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path)
    args = parser.parse_args()
    root = args.worktree.resolve()
    def git(*arguments):
        return subprocess.check_output(["git", "-C", str(root), *arguments], text=True)
    if git("rev-parse", "--short=7", "HEAD").strip() != "f48460d" or git("status", "--porcelain").strip():
        raise SystemExit("Expected a clean f48460d worktree")
    prefix = Path("src/client/java/dev/metalcraft/client")
    for name in ("metal/MetalDevice.java", "metal/MetalNative.java", "metal/MetalGpuFrameCapture.java",
                 "metal/MetalSurfaceProbe.java", "metal/MetalStallProbe.java", "metal/MetalShaderTranslator.java",
                 "test/MetalFrameMetrics.java", "test/MetalBenchmarkEnvironment.java", "test/MetalLifecycleGameTest.java",
                 "lod/LodSettings.java"):
        target = root / prefix / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(prefix / name, target)
    # Retain the old command stream ABI; backport only the native measurement hunks.
    diff = subprocess.check_output(["git", "diff", "f48460d", "--", "src/native/metalcraft.m"], text=True)
    parts = re.split(r"(?=^@@ )", diff, flags=re.M)
    patch = parts[0] + "".join(p for p in parts[1:] if "MCCommandSetPipeline" not in p)
    subprocess.run(["git", "-C", str(root), "apply", "-"], input=patch, text=True, check=True)
    source = (prefix / "lod/LodLoadedRenderer.java").read_text()
    record = re.search(r"    public record Stats\(.*?\) \{ \}", source, re.S).group()
    stats = "package dev.metalcraft.client.lod;\n/** Measurement schema only; no LOD renderer or hooks. */\npublic final class LodLoadedRenderer {\n"
    stats += record + "\n    private static final Stats EMPTY = new Stats(" + ",".join(["0"] * 26) + ");\n"
    stats += "    public static Stats stats() { return EMPTY; }\n    public static Stats sample() { return EMPTY; }\n}\n"
    (root / prefix / "lod/LodLoadedRenderer.java").write_text(stats)
    path = root / prefix / "test/MetalBenchmarkEnvironment.java"
    source = path.read_text()
    for name in ("LodCapabilities", "LodCompilerCapture"):
        source = source.replace(f"import dev.metalcraft.client.lod.{name};\n", "")
    source = source.replace("MetalCraftConfig.lod()", "LodSettings.defaults()")
    source = source.replace("LodCapabilities.EXPERIMENTAL", "false")
    source = re.sub(r"^.*MetalCraftConfig\.setLod\(.*\n", "", source, flags=re.M)
    source = re.sub(r"^.*value.add\(\"lodCapture\".*\n", "", source, flags=re.M)
    source = source.replace('value.addProperty("lodGeometryAvailable", false);',
                            'value.addProperty("lodGeometryAvailable", false);\n        value.addProperty("rendererBaseline", "f48460d pre-LOD; measurement backport only");')
    path.write_text(source)
    path = root / prefix / "test/MetalLifecycleGameTest.java"
    source = path.read_text().replace("\t\ttry (var lod = new MetalLodTestScope(context)) {\n\t\t\tthis.runConfiguredTest(context);\n\t\t}",
                                      "\t\tthis.runConfiguredTest(context);")
    source = re.sub(r'\t\tif \(Boolean.getBoolean\("metalcraft.lod\w+Test"\)\) \{.*?\n\t\t\}\n', "", source, flags=re.S)
    path.write_text(source)
    path = root / "build.gradle"
    source = path.read_text()
    properties = re.search(r"def benchmarkProperties = \[.*?\n\]", Path("build.gradle").read_text(), re.S).group()
    source = re.sub(r"def benchmarkProperties = \[.*?\n\]", properties, source, flags=re.S)
    path.write_text(source)
    # Register only the new measurement files in the diff; no commit or renderer LOD implementation.
    subprocess.run(["git", "-C", str(root), "add", "--intent-to-add", "src"], check=True)
    (root / "measurement-backport.patch").write_text(git("diff"))
    print("Prepared pre-LOD renderer with identical route, readiness, GPU timing and presentation checks:", root)


if __name__ == "__main__":
    main()
