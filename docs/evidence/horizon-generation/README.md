# Compact-horizon generation throughput — September 23, 2026

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

The active compact-horizon streamer previously allowed just 16 concurrent FULL
chunk requests on every machine. The saved configuration uses this streamer;
the older `LodTerrainGeneration` path is disabled and was not changed.

The new window is four requests per available processor, clamped to 16–64.
It admits at most 16 new requests per server tick, including edit refreshes.
At 35 ms mean server tick time it falls back to 16; at 45 ms, or 48 queued
snapshots, new admissions stop while outstanding work drains. Pending snapshots
reduce available request capacity. Existing 2 ms server sampling, 64-snapshot
queue, 16-column client intake, FULL generation, loading-only tickets, sampling
contents, terrain detail and Metal meshes are unchanged.

## Results

Apple M4 Max, Java 25, 16 GiB test heap, Default/verified Metal, None shader pack.
Each run creates a fresh NORMAL world with seed `metalcraft`, 128 render distance,
16 simulation distance, native quality 20, reduction 2 and horizon detail 1.
After the first sample, the player remains stationary for 200 server ticks
(approximately 10 seconds). The timed interval ends before lifecycle/edit checks.

| Run | Completed columns in interval | Columns/s | End mean server tick | End server-loaded chunks |
| --- | ---: | ---: | ---: | ---: |
| [Original 16-request window](before.json) | 409 | 40.90 | 3.67 ms | 2,430 |
| [Adaptive window](after.json) | 720 | 72.00 | 2.05 ms | 3,057 |
| [Final with extended checks](final.json) | 701 | 70.10 | 2.73 ms | 2,971 |

Final measured throughput is 71% higher than the baseline; the earlier successful
comparison was 76% higher. End cached-column counts were 409 before, 692 in the
first improved run, and 698 in the final run. This measures initial horizon
population, including both already available native terrain and vanilla-generated
terrain. It does not isolate noise/feature generation, prove complete 128-radius
coverage, or establish sustained speed on different CPUs/heaps/detail levels.
Startup and async generation timing vary between runs.

The larger window uses more temporary server residency: the final run observed
at most 3,341 server-loaded chunks, 64 sampler tickets and 27 queued snapshots.
Vanilla dependency, save and unload lifetimes are additional to sampler tickets.
Four-core machines retain the old maximum of 16; other memory configurations
were not qualified. No graphics-quality reduction was used to obtain the speedup.

## Correctness and validation

`./gradlew build --offline` passed, including scheduling-pressure/bounds tests,
existing compact-column geometry fixtures and Metal checks. The final live route
passed in a 29-second Gradle run with zero generation failures. It verifies:

- Generated models draw at 128 render / 16 simulation distance.
- Column (-24, -24) has a cached model while absent from the client chunk cache,
  and the server reports that it is not simulated.
- A server-side stone edit at y=300 updates the detached surface to y=301.
- Disabling while paused releases sampler tickets and cached models; enabling
  again resumes visible output; world close clears ownership.

[Final live log](final-live.log). The intermediate [rejected fixture](fixture-rejected.json)
used an unnecessarily strict requirement to reach a ring beyond radius 26 during
the short interval. It recorded zero generation failures but failed that test
assumption. The corrected fixture checks actual client absence and server ticking
state rather than assuming how far discovery must progress in ten seconds.
Production scheduling code did not change after the first improved measurement.

```bash
./gradlew build --offline
./gradlew runClient -PmetalLifecycleTest \
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.horizonGenerationTest=true' \
  --args='--graphicsBackend default' --offline
```

The before/first-after reports predate the extended detached-column/edit and peak
checks. Their timed interval and game settings match the final fixture. No water
shader implementation, terrain sampler, generation stage or mesh output changed.

Screenshot captures referenced by the showcase reports remain local and are excluded from Git.
