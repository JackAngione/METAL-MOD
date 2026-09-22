# Prepared disabled-overhead baseline

Detached pre-LOD `f48460d` renderer with the current route, receive/render readiness, GPU distributions and continuous presentation probes backported by `scripts/lod-prepare-baseline.py`. The shared texel-buffer translation correction is present in both comparison builds. The original native command-stream ABI is preserved; no active LOD capture, selection or rendering hooks are installed. The zero-valued LOD Stats type is report-schema compatibility only.

`./gradlew compileClientJava` passes in 5s. The complete reviewed backport is retained here, with its SHA-256 and base commit in `provenance.json`. No live baseline measurement is claimed: the Mac locked before those eight cases could run. The prepared local worktree is `/tmp/metalcraft-p8-pre-lod`.

When the display is available, run from the main repository:

```sh
caffeinate -di python3 scripts/lod-run-baseline.py /tmp/metalcraft-p8-pre-lod build/reports/lod-release-confirmed build/reports/lod-disabled-confirmed --resume
python3 scripts/lod-disabled-summary.py build/reports/lod-disabled-confirmed build/reports/lod-release-confirmed
```

Run sequentially with other GPU benchmarks. This compares the current experimental-capability LOD-off path with the pre-LOD renderer; it is not a separate measurement of shipping with all capability gates closed.
