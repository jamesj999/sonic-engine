# July 2026 Performance Integration Report

## Scope

This report closes the project-wide performance pass covering the audio engine,
special stages, render pipeline, memory ownership, rewind, and test throughput.
All production changes were developed in the isolated
`feature/ai-performance-optimization` worktree, reviewed independently, and
kept deterministic by exact ordering, snapshot, restore, and work-equivalence
tests.

## Integrated work

The pass was banked incrementally into `develop`. Principal commits include:

- `c96eaa21c`, `f914a8678`, `66558fdb1`: audio history ownership, command
  allocation removal, and fused stereo FIR traversal.
- `9f81ba551`, `e860f62f3`, `e5f25c86e`: palette, S3K visibility, and special
  stage static/decode caching.
- `648834710`, `efabd60b5`, `63df03205`: frame-owned render state, zone render
  scratch, overflow batching, and GL lifecycle hardening.
- `80191c934`, `c9812461b`, `1ffa90c4b`, `4435b4ea5`: incremental background
  uploads, inactive render gating, warm rewind lookup, and test-scan reuse.
- `7fe084adf`: shared composite rewind layouts.
- `adb10a3b5`, `18e39d7f4`: allocation-free S2 special-stage ordering and
  canonical empty render snapshots.
- `c371e4403`, `bcad88633`: primitive animated-tile live state and compact
  layout-backed rewind snapshots.
- `dc5e099e5`: capture-local SMPS fallback descriptor deduplication.

The final closure diff after the last `develop` checkpoint changes only the
audio driver, animated-tile graph/snapshot, their focused tests and benchmark
estimator, `CHANGELOG.md`, `README.md`, and this report.

## Measured results

| Area | Workload | Baseline | Final | Improvement |
|---|---|---:|---:|---:|
| Audio memory | Default unused PCM rewind history | ~11 MiB allocated | Lazy, 0 MiB while unarmed | ~100% unused allocation removed |
| Render upload | Normal scrolling BG update | 8,192 B | 256 B | 96.875% |
| Render upload | Wide scrolling BG update | 32,768 B | 256 B | 99.219% |
| Rewind capture | 24-subsystem composite snapshot | 2,752 B | 192 B | 93.0% |
| S2 special stage | 32 objects + 8 players ordering | 472 B | 0 B | 100% |
| Render rewind | Empty special/advanced render captures | ~600 B | 0 B | 100% |
| Animated tiles | 32-channel live update | 1,792 B | 1,280 B | 28.6% |
| Animated rewind | 32-phase capture | 3,288 B | 320 B | 90.3% |
| Animated rewind | 1,000 retained snapshots | 2,378,048 B | 356,728 B | 85.0% |
| Animated rewind | Same-layout restore | non-zero keyed restore | 0 B | allocation-free |
| SMPS snapshot | 32 SFX sharing 256 KiB fallback | 27,784 B | 26,296 B | 5.4% |
| SMPS snapshot | Same capture time | 4,958,530 ns | 178,580 ns | 96.4% |
| SMPS snapshot | Full fallback hashes / 20 captures | 640 | 20 | 32x fewer |
| Test guards | Focused source-guard median | 51.49 s | 43.56 s | 15.4% |

The optimized EHZ rewind benchmark reduced retained animated-tile state from
8,792 to 3,560 bytes across 21 keyframes, and total estimated retained state
from 161,872 to 156,640 bytes, relative to the identical baseline invocation.

## Verification commands

The final branch was verified with:

```powershell
mvn -Dmse=off test
mvn -Dmse=off package
mvn -Dmse=off "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Ds2.rom.path=s2.gen" test
```

The default suite produced 1,504 fresh Surefire reports: 11,504 tests,
0 failures, 0 errors, and 12 skipped. Focused performance tests additionally
assert exact callback/entry counts, exact snapshot maps and descriptors,
volatile escape, immutable ownership, and raw allocation samples.

The opt-in long-tail command was run at baseline `c97cbff87` and at the final
optimized head. Both were clean for 60- and 120-frame rewinds and first failed
the 300-frame attempt with the same `object-manager.dynamicId` signature and
the same 120-frame longest-clean frontier. The optimization therefore did not
move the existing frontier. The 1,200-frame gate remains open; it was not
lowered, excluded, or given a trace-specific exception.

## Disproved candidates

Two stage-aware render gates were benchmarked and rejected:

- Immediate empty-stage dispatch allocated 0 bytes in both current and gated
  forms across all seven repetitions.
- Priority-mask capture initially appeared to save 32 bytes, but the isolated
  warmed rerun produced no qualifying allocation or timing win in any of seven
  repetitions.

All experimental code was removed. No speculative stage gate was committed.

## Deferred work and residual risks

- The `object-manager.dynamicId` rewind divergence is a real, separately scoped
  determinism defect. It blocks the opt-in 1,200-frame benchmark but is
  unchanged by this pass.
- Broad trusted/owned factories for level-event, palette, ring, game-state, and
  zone-runtime snapshots were deferred: current evidence does not justify the
  aliasing and public-immutability risk.
- Persistent SMPS descriptor caching was rejected because source byte arrays
  are exposed and may mutate. Deduplication remains capture-local.
- Mutable/reused `ChannelContext` objects were rejected because public
  callbacks may retain them; fresh immutable context identity is preserved.
- GPU architecture, object snapshot slabs, and further segment-cache redesigns
  require new profiling evidence and are outside this completed pass.

## Acceptance mapping

| Requirement | Evidence |
|---|---|
| Audio engine improved | History ownership, command/FIR work, and SMPS descriptor dedup measurements |
| Special stages improved | Cached static/decode data and zero-allocation stable S2 ordering |
| Render pipeline improved | Incremental uploads, frame-owned scratch, batching, and verified GL lifecycle |
| Memory optimized | Lazy PCM history, shared snapshot layouts, canonical empties, compact animated snapshots |
| Rewind improved | Warm lookup, compact captures, zero-allocation same-layout restore, unchanged frontier signature |
| Determinism preserved | Exact semantic oracles, baseline/final long-tail signature comparison, green default suite |
| Tests pass | 11,504 tests, 0 failures/errors, 12 skipped |
