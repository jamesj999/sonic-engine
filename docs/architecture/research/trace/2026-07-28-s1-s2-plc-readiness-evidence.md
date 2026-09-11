# S1/S2 PLC readiness evidence

Date: 2026-07-28

## Disposition

`NATIVE_MODEL_APPROVED`. The varied-history corpus is sufficient to proceed
with the native logical-queue design after the S3K direct Kos decompression
queue is integrated. No engine queue/runtime behaviour was changed by this
evidence task, and this document does not authorize trace hydration or a
hardware-timing event kind.

## Structural findings

Both retail queues are sixteen six-byte entries at `0xFFF680`; neither
`AddPLC`/`LoadPLC` checks capacity. S1 services 9 patterns for title,
title-card, ending, and fade handlers, and 3 for level and paused handlers;
Continue does not service PLC. S2 uses 6-pattern handlers for title/title-card,
results/fade, and menu paths, and `ProcessDPLC2`'s 3-pattern handler for level
and paused paths; ending does not service PLC. Both `RunPLC` and
`RunPLC_RAM` publish `PatternsLeft` before decoder preparation in the retail
build. A clear or replacement zeroes the buffer but not all decoder scalars,
so capture must prove it occurs only while idle before a logical queue can
model it.

## Tooling and attempted captures

The diagnostic Lua scripts write only to an explicit
`OGGF_PLC_PROBE_OUTPUT` path and reject an existing path. They refuse to run
until reviewed environment-supplied addresses pin the RAM fields, VInt/HInt
sampling, the explicit lag-handler value, each preparation/service/pop
boundary, replacement and standalone-clear completion, and every consumer.
The pop completion hook conditionally emits empty only when the shifted queue
is actually empty; it also supplies the completion-through-pop service edge,
while both full and small entry paths share one partial-return post hook. That
shared return is also the ordinary empty-PLC fast path: with no captured
service pre-state it emits nothing, while a captured zero-pattern transition
still fails closed. A stock-Lua behavioral harness loads each probe unchanged
and executes empty full/small calls, partial calls through both entries,
completion through pop, and the malformed zero-pattern case. This is
intentional: routine-entry placeholders and source-text assertions cannot
yield approval. The probes' JSONL records contain raw frame/order, handler,
lag, HInt deferral, queue head/destination/count, and slot count. Replacement
is emitted only after its post-copy boundary and requires idle decoder
snapshots before and after; its nested retail clear is represented atomically
rather than as a false replace-then-clear queue transition. They are
deliberately separate from canonical trace recorders.

ROM hashes were verified:

| game | SHA-1 |
|---|---|
| S1 World REV01 | `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b` |
| S2 World REV01 | `8bca5dcef1af3e00098666fd892dc1c2a76333f9` |

## Reviewed diagnostic hook configurations

The sourceable configurations
`tools/bizhawk/diagnostics/s1_plc_timing_probe.env.sh` and
`tools/bizhawk/diagnostics/s2_plc_timing_probe.env.sh` are the only approved
address inputs for a future capture. They require a caller-selected, absent
`OGGF_PLC_PROBE_OUTPUT`; sourcing a configuration does not launch BizHawk.
Both use BizHawk `mainmemory` offsets rather than 24-bit CPU addresses:
`buffer=0xF680`, `destination=0xF684`, `patterns_left=0xF6F8`,
`game_mode=0xF600`, and `vint_selector=0xF62A`. Each publishes the reviewed
consumer-hook list for its covered lifecycles.

Every address below was byte-checked against the named REV01 ROM. Execute
callbacks run before the listed opcode, so begin hooks retain the PLC id or
pre-state, while post hooks are the first instruction after the retail copy
or final state store.

| boundary | S1 PC / bytes | S2 PC / bytes |
|---|---|---|
| append begin | `0x1578` / `48 E7 00 60` | `0x161E` / `48 E7 00 60` |
| append post-copy | `0x15A4` / `4C DF 06 00` | `0x164A` / `4C DF 06 00` |
| replace begin / post-copy | `0x15AA` / `48 E7 00 60`; `0x15D0` / `4C DF 06 00` | `0x1650` / `48 E7 00 60`; `0x1676` / `4C DF 06 00` |
| clear begin / post | `0x15DA` / `70 1F`; `0x15E2` / `4E 75` | `0x167C` / `45 F8 F6 80`; `0x1688` / `4E 75` |
| prepare begin | `0x15F0` / `20 78 F6 80` | `0x1696` / `20 78 F6 80` |
| early `PatternsLeft` publish | `0x160A` / `31 C2 F6 F8` | `0x16B0` / `31 C2 F6 F8` |
| final prepare store / true shared return | `0x1634` / `21 C6 F6 F4`; `0x1638` / `4E 75` | `0x16DA` / `21 C6 F6 F4`; `0x16DE` / `4E 75` |
| full / small active service pre | `0x1642` / `31 FC 00 09 F6 FA`; `0x165C` / `31 FC 00 03 F6 FA` | `0x16E8` / `31 FC 00 06 F6 FA`; `0x1702` / `31 FC 00 03 F6 FA` |
| partial return / pop pre / post | `0x16D2` / `4E 75`; `0x16D4` / `41 F8 F6 80`; `0x16E2` / `4E 75` | `0x1778` / `4E 75`; `0x177A` / `41 F8 F6 80`; `0x1788` / `4E 75` |
| VInt selection | `0x0B14` / `4A 38 F6 2A` | `0x0408` / `48 E7 FF FE` |
| deferred-HBlank entry | `0x119E` / `42 38 F6 4F` | `0x1072` / `42 38 F6 4F` |

The preparation-return PC is also reached by empty and already-active guards.
The probes therefore arm `preparing` only at the active-path begin and emit a
single end edge only if that arm is present. They similarly capture append id
and pre-state at begin and emit its submission only at the post-copy hook.
At the VInt hook they sample the selector before the ROM clears it;
`lag = (selector == 0x00)`. The HBlank hook marks the deferred path before it
clears the deferral latch, so subsequent small-service records retain both the
selected VInt identity and the HBlank classification.

The native headless harness was exercised successfully with the S1 GHZ movie:

```bash
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
tools/bizhawk-headless/run.sh --mode trace --rom s1.gen \
  --movie src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2 \
  --output /tmp/<scratch>
```

It reported BizHawk 2.11, the verified S1 hash, 4,806 movie frames, and a
3,905-frame trace. That harness does not expose execute hooks and therefore
cannot produce the isolated PLC event stream.

The initial sandboxed launches could not reach X, but host-level `DISPLAY=:0`
access was subsequently verified and used for execute-hook capture. The
reviewed configurations above produced two complete, byte-identical captures
for S1 GHZ1 and S2 ARZ2:

The captures used BizHawk 2.11 with probe revision `3135d0826` (including the
earlier structural, movie-exit, fast-headless, and buffered-output commits).
Each ordinary route used this command shape:

```bash
export OGGF_PLC_PROBE_OUTPUT=/tmp/<route>/<game>.jsonl
source tools/bizhawk/diagnostics/<game>_plc_timing_probe.env.sh
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/diagnostics/<game>_plc_timing_probe.lua <movie.bk2> <game>.gen
mvn exec:java \
  "-Dexec.mainClass=com.openggf.tools.PlcTimingEvidenceTool" \
  "-Dexec.args=--game <game> --rom <game>.gen \
  --probe /tmp/<route>/<game>.jsonl --out /tmp/<route>/vector.json"
```

The movie corpus was:

| route | movie | capture window |
|---|---|---|
| S1 GHZ1 | `src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2` | full movie |
| S1 MZ1 | `src/test/resources/traces/s1/mz1_fullrun/s1-mz1.bk2` | full movie |
| S1 maze round trip | `src/test/resources/traces/s1/runs/s1-ghz-maze-roundtrip/s1-ghz-maze-roundtrip.bk2` | full movie |
| S1 Final Zone | `src/test/resources/traces/s1/_movies/s1-complete-run.bk2` | `OGGF_PLC_CAPTURE_START=188500`, `OGGF_PLC_CAPTURE_STOP=195495` |
| S2 EHZ1 | `src/test/resources/traces/s2/ehz1_fullrun/s2-ehz1.bk2` | full movie |
| S2 ARZ | `src/test/resources/traces/s2/arz2/s2-lvl-select-ARZ.bk2` | full movie |
| S2 special stage | `src/test/resources/traces/s2/special_stage/s2-lvl-select-special-stage.bk2` | full movie |

| route | raw records | raw SHA-256 | vector SHA-256 | analyzer |
|---|---:|---|---|---|
| S1 `ghz1_fullrun` | 9,912 | `141bd5a2be4ea8f53de7ef7fcaa198b382b3a29b502b5f93d231554f154435bc` | `8b55a3771f5a9aaefdd4ff3ce02d30206ad72e1b4cb24d82f03b7bd1df775ed0` | match |
| S2 `arz2` | 33,163 | `f87ae9260b6f60d14d9da23bf944595430de4bbd454d21502d456899d6b4ec80` | `20e0c58dbab71a10fea0f877059813daf4619d98999829075eb61157e2126e7a` | match |

Each raw stream and derived vector was byte-identical across the two runs.
This is a recorder-stability smoke test, not evidence that native timing
generalizes across different queue histories.

The model gate was then exercised against materially distinct executions:

| game / execution | consumer coverage | observed poll diversity | vector SHA-256 | analyzer |
|---|---|---|---|---|
| S1 GHZ1 individual | title, results | title 107 busy; results 42 busy | `8b55a3771f5a9aaefdd4ff3ce02d30206ad72e1b4cb24d82f03b7bd1df775ed0` | match |
| S1 MZ1 individual | title, results | title 103 busy; results 42 busy | `528812fafa0eed270482132bb92089a1580675167f595587fe4a91c25a7be97e` | match |
| S1 GHZ/maze round trip | title, results, special results/exit | two title instances; special results 16 busy | `72a35a648d34319585dcfd65e4a588005b55e04e788a93e1d56f4eaa821ed96a` | match |
| S1 complete-run Final Zone window | title, Final Zone boss | title 132 busy; boss 118 busy across four nonzero sources | `9fb6502e67d1c30252bbdbccf3933a42524c5c3640bb6747c0825cef0ec82ed9` | match |
| S2 EHZ1 individual | title, results | title 7 busy; results 64 busy across four nonzero sources | `4b034bb06fe3d2824feade341a80bc3365c14023cd2726d6f4ecbd330f8e99f3` | match |
| S2 ARZ level-select | title, two results instances, ARZ boss | results 126 busy total; boss 70 busy across two nonzero sources | `20e0c58dbab71a10fea0f877059813daf4619d98999829075eb61157e2126e7a` | match |
| S2 dedicated special stage | title, special-stage results | special results 23 busy across four nonzero sources | `076753305897397eec9092bba363ac70202047e3fced5d2642248ebe7ded0108` | match |

The structural histories are measurably different:

| execution | submissions | services | lag VInts | HBlank deferrals | preparations / pops |
|---|---:|---:|---:|---:|---:|
| S1 GHZ1 | 14 | 349 | 39 | 0 | 34 / 33 |
| S1 MZ1 | 11 | 311 | 37 | 0 | 30 / 30 |
| S1 GHZ/maze round trip | 17 | 502 | 139 | 0 | 61 / 61 |
| S1 Final Zone window | 10 | 481 | 254 | 145 | 38 / 37 |
| S2 EHZ1 | 12 | 212 | 64 | 0 | 28 / 28 |
| S2 ARZ | 31 | 676 | 289 | 623 | 84 / 83 |
| S2 special stage | 11 | 139 | 2,036 | 0 | 27 / 27 |

These executions do not have byte-identical PLC histories. They cover
different level art sets, multi-stage detours, a full-run Final Zone
transition, three/six/nine-pattern service regimes, and distinct consumer
latencies/source sequences. The structural predictor matches every captured
edge without consuming recorded PLC progress or poll results. This directly
supports the claim that native service timing is determined by ROM
submissions plus structural interrupt/service state rather than by a
route-specific trace completion signal.

No available BK2 in the audited corpus reaches the Game Over consumer. That
consumer is unavailable evidence, not a native-model mismatch. The common
title/results families have varied-history coverage; special results and the
unique Final Zone/ARZ boss consumers have authentic route coverage.

The probes close and exit BizHawk when the movie reaches `FINISHED`, avoiding
host-speed-dependent timeout truncation. Complete-run windows use the
template-derived start/stop controls to preserve authentic playback while
emitting only the relevant interval.

All seven S1 and twelve S2 clear/replace observations on these routes had
`patterns_left_before=0` and `patterns_left_after=0`. No event occurred
between any captured preparation begin/end pair, so the retail preparation
race disposition for these covered routes is
`NOT_OBSERVED_ON_COVERED_ROUTES`. This does not prove the same facts for the
Game Over, the only unavailable consumer in the BK2 corpus. Final Zone and
both special-stage result paths are covered by the executions above.

## S2 smoke ordering correction

No new capture was launched for this correction. The supplied real S2 smoke
stream at `/tmp/openggf-s2-plc-smoke.swj6qH/s2.jsonl` was inspected with the
read-only smoke command:

```bash
capture=/tmp/openggf-s2-plc-smoke.swj6qH/s2.jsonl
wc -l "$capture"
jq -r '.event' "$capture" | sort | uniq -c
```

It contains 17,041 records: 15,853 `plc_frame_state`, 676 `plc_service`, 214
`plc_consumer_observation`, 84 each of `plc_prepare_begin` and
`plc_prepare_end`, 83 `plc_pop`, 31 `plc_submission`, and 16 `plc_empty`.
The first ordering defect is at raw frame 117, where `plc_frame_state` and
`plc_submission` both claim `within_frame_order=1`; 717 raw frames have a
non-increasing order sequence.

`PlcTimingEvidenceTool` rejects that stream before evidence derivation with
`probe records must be ordered by raw_frame and within_frame_order`. This is
an intentional, independent analyzer failure, not a condition relaxed by the
probe change. The defect was that both probes reset their sequence in the
BizHawk frame-end callback even when a later execute hook still reported the
same `emu.framecount()`. Both probes now reset only when `emit()` observes a
changed raw frame. The executable Lua contract invokes the frame-end callback
followed by a same-frame append completion and asserts strictly increasing
orders for every shared raw frame; it passes for both S1 and S2 after the
change. The seven accepted captures documented above were recorded after this
correction.

## Structural state ordering correction

No new capture was launched for this correction. The same supplied smoke
stream exposes a second issue at raw frame 421: `plc_frame_state` is written
with handler `0x00` and `lag=true` before the next VInt services six patterns
under handler `0x12` and `lag=false`, while BizHawk still reports raw frame
421. The frame-end callback is therefore an emulator boundary sample, not the
identity of every later ROM event carrying the same `emu.framecount()`.
Grouping by raw frame made the predictor use stale lag state and false-reject
the service.

Both probes now publish `plc_vint_state` at the reviewed pre-clear VInt hook
and `plc_hblank_state` at the reviewed deferred-HBlank entry. These are
ROM-control-flow facts independent of service, pop, empty, and consumer
results. They share the existing sequence generator, so frame-end, VInt,
HBlank, and oracle records retain strict total order even when the raw frame
does not advance.

`PlcTimingEvidenceTool` now derives an ordered structural/action timeline. A
frame-end state is passive. A VInt state supplies an immediate service point
only when the reviewed handler budget, non-lag state, and active decoder allow
it. A legal HBlank transition marks the open VInt deferred and anchors its
single service point at HBlank. Submission, completed preparation, and
consumer execution identities retain their positions. Oracle result fields
cannot create, finalize, or reclassify structural state.

The Lua contract reproduces frame-end followed by a changed VInt and service
under one raw frame, then a deferred HBlank transition before small service.
The Java CLI contract deliberately gives the later service/pop/empty/consumer
records stale handler, lag, and HBlank fields; analysis still matches the
independently published structure and emits `HBLANK_SERVICE`. The disposition
is `NATIVE_MODEL_APPROVED`; the varied-history captures above supply the
required lifecycle evidence.

## Rerunnable analyzer

`PlcTimingEvidenceTool` derives pattern counts directly from the supplied ROM
Nemesis headers. Dedicated `plc_frame_state`, `plc_vint_state`, and
`plc_hblank_state` records are the only sources of interrupt structure; it
never builds or reclassifies a segment from service-oracle records. The tool
requires a consumer observation and independently sampled progress at the
preparation and service boundaries, slot-count reduction at pop, and a
zero-slot post-shift empty observation. It then compares its predicted
prepare/service/pop/empty/poll edges against the oracle. Its unit test mutates
handler, lag, HInt deferral, preparation, poll order, and budget; each
mutation rejects the oracle.

The committed gzip contains the seven reviewed derived vectors and route
identities. Raw execute-hook streams remain scratch-only. Independent design
review approved the varied-history criterion and concluded that unavailable
Game Over coverage does not block the gate: it adds no new mechanism beyond
the already-covered ROM append, ordinary level-service budget, and
whole-queue readiness poll.
