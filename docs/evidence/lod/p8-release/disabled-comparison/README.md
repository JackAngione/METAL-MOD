# Disabled-versus-pre-LOD comparison

Partial capture set: **2 of 8 pairs complete**, on one frozen current-source identity. Both native None/full and None/half pairs pass the <=2% median / <=5% p99 frame-interval limits in all three route phases. Each process records three repeats on NORMAL seed `metalcraft`, Default/Metal, 16/16; current and `f48460d` run back-to-back with alternating case order. The baseline retains the original renderer plus the auditable measurement backport and shared texel-buffer correctness correction. Commands omit development and terrain-census flags.

The idle-path refinement skips LOD maintenance and per-draw census during ordinary off play, retaining nonblocking GPU retirement after disable. Accepted current reports show zero terrain captures, LOD uploads/draws/census, preparation and charged mesh bytes. This does not establish the complete eight-case disabled target. The user waived the remaining long runs for P8 completion on 2026-09-12.

Native None/full median changes are −9.53% stationary / +0.96% pan / −0.93% traversal; p99 changes are −3.70% / +3.83% / −7.78%. Native None/half median changes are +1.34% / +1.41% / −5.92%; p99 changes are −1.74% / −0.21% / −2.56%. These are medians of repeat percentiles, not pooled percentiles or isolated hook costs. The inspected native None/full images match near terrain but have slightly different distant coverage and player skins.

The subsequent native Standard/full baseline exhausts three attempts because Minecraft loses foreground. Those rejected runs are retained separately in `../disabled-focus-rejected`. The user subsequently instructed us to skip the remaining six pairs and final horizon/enable-disable rerun. P8 is closed with this explicit waiver; the missing measurements remain unmeasured and no final horizon result is claimed for the idle refinement.

Raw reports, invocation/source provenance and inspected images are retained. `images.json` lists hashes and local paths for every completed screenshot. Successful full logs stay in the ignored build report; concise phase/completion logs are archived here. Rejected attempts retain full logs. The optional full-matrix summary command still requires all eight pairs and deliberately refuses this partial set; the waiver does not change its validation rules:

```sh
python3 scripts/lod-disabled-summary.py docs/evidence/lod/p8-release/disabled-comparison/pre-lod docs/evidence/lod/p8-release/disabled-comparison/current
```
