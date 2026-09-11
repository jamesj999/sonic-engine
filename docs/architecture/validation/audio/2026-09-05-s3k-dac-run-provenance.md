# S3K DAC run provenance and missing tempo-control input

## Scope and result

Investigation baseline: `develop` at `bbf28b7dc`; isolated branch
`bugfix/ai-audio-next-dac`. Reference: committed
`src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz`,
with the committed request-observation sidecar. ROM: the main checkout's
`s3k.gen`, verified by the capture against SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6`.

The run-338 `88` versus `7F` mismatch does **not** establish a DAC decoder
defect. The two run ordinals refer to different plays, 179 services apart.
The earliest DAC track scheduling divergence is service 2940, when the
reference's external tempo-speedup control becomes 8 while the engine's
mailbox-only capture has received no such input. The first DAC-enable
schedule difference follows at service 2943. No decoder, cadence,
playback, snapshot, reference data, or comparison criteria were changed.

The delivered correction adds both run-start service ordinals to text and
JSON DAC mismatch reports. These are the services containing each run's
first byte, not the service of the mismatching byte. They are diagnostic
provenance and never realign runs or alter the mismatch result.

## Run evidence

`DebugS3kDacRuns` annotates the existing capture without changing its inputs
or state. Every `2B` boundary, first-byte service/write position, preceding
service's DAC note, length, and first/last twelve bytes are printed for the
selected range. The note is a logical track observation, not an additional
physical sample-submission event.

| Side / run | First byte: service / write | Preceding DAC note | Bytes | First twelve bytes |
|---|---|---|---:|---|
| Reference 337 | 3653 / 1 | `8C` | 192 | `7E 76 66 56 5E 6E 7E 86 88 89 8B 8F` |
| Engine 337 | 3831 / 1 | `8C` | 192 | `7E 76 66 56 5E 6E 7E 86 88 89 8B 8F` |
| Reference 338 | 3658 / 1 | `84` | 558 | `88 98 B8 C0 C2 82 42 32 42 82 80 A0` |
| Engine 338 | 3837 / 1 | `81` | 3087 | `7F 7F 7B 73 71 73 74 70 70 78 80 88` |
| Reference 339 | 3664 / 2 | `86` | 1438 | `80 7C 8C 7C 6C 8C 88 48 68 A8 68 58` |
| Engine 339 | 3847 / 1 | `81` | 3482 | `7F 7F 7B 73 71 73 74 70 70 78 80 88` |

Reference run 337 terminates with `2B=0` at service 3654/write 69; engine
run 337 terminates at 3831/write 193. Reference run 338 is opened by
`2B=80` at 3658/write 0 and ends at the next enable at 3664/write 1.
Engine run 338 is opened at 3837/write 0 and ends at 3847/write 0.
Service 3836 consumes music request `2C` on both sides. Reference run 363,
at service 3837/write 2, begins with the same `7F 7F 7B 73 ...` bytes as
engine run 338. Reference run 362 is the separate one-byte `5A` run at
3837/write 0, before the enable. That authenticated idle byte is an existing
reported discrepancy, not removed by this work.

The initial coincidence that `7F` is also the final byte of the preceding
`8C` sample was insufficient evidence of a stranded byte. The complete
prefix and service coordinates reject that explanation for this mismatch.

The full comparison retains 475 reference runs versus 449 engine runs.
Its first byte mismatch remains run 338/byte 0 after 387160 compared bytes;
run-length delta 49916, sample-end delta 34, idle-byte delta -1. The new
report fields are `reference_run_start_service: 3658` and
`openggf_run_start_service: 3837`. The separate service comparator still
stops at service 1570, decoded-write event 39, PSG `E7` versus `FF` on this
isolated baseline. This work does not assert anything about that parallel
workstream's corrected result.

## Earliest scheduling divergence and owning input boundary

| Service | Reference / engine tempo accumulator | Reference / engine speedup | Reference / engine DAC duration |
|---|---|---|---|
| 2938 | 228 / 228 | 0 / 0 | 4 / 4 |
| 2939 | 3 / 3 | 0 / 0 | 4 / 4 |
| 2940 | 65 / 34 | 8 / 0 | 2 / 3 |

All three mailbox bytes are zero at 2940; its movie frame is 3073.
Reference speedup timeout is 6, engine timeout is 0. Both still have DAC
note `8A`. `DebugS3kDacRuns` compares frequency and duration from the start
of the capture and reports 2940 as the first difference while both DAC
tracks are playing. It separately checks nonzero `2B` counts and first
reports service 2943 (reference 1, engine 0), then 2944 (0, 1).

Retail source uses `fix_sndbugs=0`
(`docs/skdisasm/Sound/Z80 Sound Driver.asm:16`). The relevant owners are:

- `sonic3k.asm:1517-1522`, `Change_Music_Tempo`: stop the Z80, write `d0`
  directly to `Z80_RAM+zTempoSpeedup`, restart the Z80. This is a separate
  input address from the three request mailboxes.
- `sonic3k.asm:40820-40841`, `Monitor_Give_SpeedShoes`: set the speed-shoes
  state and timer, then call `Change_Music_Tempo` with `d0=8`.
- `Sound/Z80 Sound Driver.asm:743-758`: the SFX/music tail checks
  `zTempoSpeedup`, reloads `zSpeedupTimeout`, and performs extra music work.
- `S3kOpenGgfAudioCapture.capture` submits only the three mailbox values;
  its dispatch does not convey the separate external tempo write.
  `S3kRequestObservationSidecar` authenticates music-mailbox observations
  at `1C0A`, not tempo-control writes at `1C08`.

This identifies missing producer-control coverage in the capture contract.
The existing compared RAM tells us the control changed, but is not an
authorized source from which to synthesize a production input. A follow-on
producer capture must observe the actual external tempo-control write and
its service admission. Do not call `setSpeedMultiplier` from comparison
snapshots, invent a mailbox command, or derive an input from service 2940.
This finding does not prove the engine's complete speedup behavior correct
once that missing input is supplied.

## Physical handoff and cadence checks

`TestYm2612DacHandoff` uses two synthetic, distinguishable samples and the
existing opt-in physical YM observer. It starts an actual `11` stream,
queues a burst of ordinary YM writes followed by a new DAC play and enable,
then checks the new physical `2A` bytes are exactly `E1 E2 E3 E4`, with no
old byte after the handoff. The enable's logical callback is present before
any of the queued physical operations run. Physical address/data strobes
are decoded independently in the test. It does not restore snapshots or
load trace state. This is a bounded characterization of the existing
handoff, not a claim of cycle-accurate DAC interruption or enable timing.
An exploratory expectation that old bytes must stream during queue draining
failed; no playback change was made to force that expectation.

The cadence suspicion is independently resolved only to its arithmetic:
the annotated total at `Sound/Z80 Sound Driver.asm:4351` sums to 303 Z80
cycles, including two annotated `+3` ROM-read delays. Removing those two
estimates gives 297. The source at 4301 says approximately 3.3 cycles per
ROM access. This does not establish 303 as an exact replacement period,
and neither number explains comparing note `84` with note `81` at
different services. The loader's existing 297 was left unchanged.

## Reproduction and verification

Run from the DAC worktree on Maven's JDK 21. Outputs remain in that
worktree's `target/`. Set `OPENGGF_ROM_DIR` to the absolute path of the main
checkout containing the verified ROMs.

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -Dtest=DebugS3kDacRuns \
  -Ds3k.dac.probe=true \
  -Ds3k.rom.path="$OPENGGF_ROM_DIR/s3k.gen" test
```

`s3k.dac.probe.firstRun` and `s3k.dac.probe.lastRun` optionally change the
printed run range (defaults 335 through 341). They affect diagnostic output
only. A final run with no terminating `2B` is included and explicitly marked
`unterminated=true`; EOF never fabricates a disable. Without
`s3k.dac.probe=true`, the explicit probe is disabled.

The report regression was run first against the original comparator:
`LUA_BIN=lua5.4 mvn -Dmse=off -Dtest=TestS3kAudioParityComparator test`.
It failed at the missing run-start service provenance assertion (11 tests,
1 failure, 0 errors). With the additive report change it passed.

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  -Dtest=TestYm2612DacHandoff,TestYm2612DacTiming,TestYm2612ChipSnapshot,TestChipWriteObserver,TestSmpsSessionSnapshot,TestS3kAudioParityComparator,TestS3kAudioOracleFixtureContract,TestS3kAudioOracleFixtureContractV2,DebugS3kDacRuns \
  -Ds3k.dac.probe=true \
  -Dsonic1.rom.path="$OPENGGF_ROM_DIR/s1.gen" \
  -Dsonic2.rom.path="$OPENGGF_ROM_DIR/s2.gen" \
  -Ds3k.rom.path="$OPENGGF_ROM_DIR/s3k.gen" test
```

Focused result: 47 tests, 0 failures, 0 errors, 0 skipped. The probe's
successful execution means its diagnostics completed, not that the printed
oracle mismatch is a pass. Full baseline and integrated-suite comparison
remain the coordinating branch's delivery responsibility.

## Review follow-up: unterminated final run

The review identified that the diagnostic printer initially emitted a run
only on a following `2B`, although the comparator also includes the final
run at EOF. `TestS3kDacRunDiagnostics` reproduced that omission with a
selected final run spanning two services: 2 tests, 1 failure, 0 errors/skips
before the fix. The printer now flushes nonempty selected data at EOF,
retaining its start and byte summary and marking it unterminated.

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  -Dtest=TestS3kDacRunDiagnostics,TestS3kAudioParityComparator,DebugS3kDacRuns \
  -Ds3k.dac.probe=true \
  -Ds3k.dac.probe.firstRun=448 -Ds3k.dac.probe.lastRun=474 \
  -Ds3k.rom.path="$OPENGGF_ROM_DIR/s3k.gen" test
```

The regression also covers an empty window and rejects a fabricated terminal
write. This follow-up changes only diagnostic test/probe code and this
validation record; no production comparator or playback behavior changes.
Result: 14 tests, 0 failures, 0 errors, 0 skipped, including the enabled
ROM-backed tail probe (`target/dac-final-run-green.log`).
