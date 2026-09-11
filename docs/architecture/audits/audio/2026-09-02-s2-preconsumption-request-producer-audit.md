# Sonic 2 pre-consumption request-producer audit

**Date:** 2026-09-02

**Scope:** point-in-time, read-only audit of the Sonic 2 request boundary needed to advance
the authenticated sound-driver comparison past tick 210.

**Status:** design and implementation prerequisite only; no capture, fixture, producer binding,
or comparison authority was created by this audit.

**Authority result:** `REFERENCE_LIMITATION` /
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` remains in force.

## Conclusion

The pinned native observer already exposes the primitive needed to observe the Sonic 2 request
before the Z80 consumes it. ABI 4 action 7 records an exact M68K A7 marker, while the managed
host can register a pre-execution M68K callback and read D0, D1, and A7. Sonic 1 already uses
this reviewed native-marker/managed-register correlation pattern. The current Sonic 2
complete-audio producer does not use it: its manifest has no `sndDriverInput` hook, its runner
registers no M68K callback, and its raw stream contains only post-advance Z80 state and native
service/chip events.

The missing Sonic 2 observation point is closed and source-owned. At ROM PC `0x10D6`, before
opcode bytes `13 80 10 09` execute, D0.b is the request byte accepted from the M68K queue,
D1.w is the destination slot index, and A7 is the exact native/managed correlation token. A
producer can therefore record the transfer without inferring it from later Z80 RAM, a live
sound pointer, chip output, a fixture row, or audible behavior.

This capability is not yet implemented for Sonic 2 and cannot presently publish authoritative
evidence. In addition, the current Java diagnostic seam directly admits at most one request per
tick. That is not the ROM queue contract: multiple M68K transfers may precede a later
`zCycleQueue`, and the Z80 independently scans, clears, prioritizes, and admits its queue. Both
the producer observation and the ROM-shaped OpenGGF queue/admission path are prerequisites to
an independent request comparison.

## Audited evidence and boundaries

The audit used the pinned Sonic 2 World REV01 identity, SHA-1
`8bca5dcef1af3e00098666fd892dc1c2a76333f9`, and the source and tooling already present in
this repository and its pinned `tools/tracechaser` submodule. It did not start BizHawk,
EmuHawk, GPGX, Mono, or any capture process. It did not create or edit a trace, raw stream,
fixture, capability, or executable.

Primary evidence:

- `docs/s2disasm/s2.asm:539-541` calls `sndDriverInput` during V-int while the Z80 bus is held.
- `docs/s2disasm/s2.asm:1270-1331` owns the M68K-to-Z80 transfer routine.
- `docs/s2disasm/s2.constants.asm:1877-1887` owns the M68K sound-queue layout.
- `docs/s2disasm/s2.sounddriver.asm:1496-1535` owns Z80 queue scanning and admission.
- `docs/architecture/research/audio/2026-08-30-s2-sound-driver-routine-map.md:95-98`
  and `:127-139` document the driver queue, the FixBugs-off fourth-slot alias, and the source
  interface.
- `docs/architecture/research/audio/2026-08-30-s2-driver-oracle.md:185-239` records the
  current tick-210 diagnostic frontier and the prohibition on deriving a request from its
  consequences.

## Exact pre-consumption transfer boundary

The accepted SFX/command transfer is:

| Field | Exact value and meaning |
|---|---|
| ROM PC | `0x10D6` |
| Opcode bytes | `13 80 10 09` |
| Instruction | `move.b D0,$09(A1,D1.w)` |
| Source | `Sound_Queue.SFX0[D1]`, address `0xFFFF_FFE1 + D1` |
| Destination | `zAbsVar.Queue0[D1]`, Z80 `0x1B89 + D1`, bus `0xA01B89 + D1` |
| Request | pre-operation `D0.b`, required nonzero |
| Slot | pre-operation `D1.w`, canonical range 0 through 3 |
| Correlation token | exact pre-operation A7, encoded as the ABI 4 action-7 marker payload |

The immediately preceding instructions establish the acceptance semantics:

1. PC `0x10C6` loads the M68K source slot into D0.
2. PC `0x10CA` skips a zero source.
3. PC `0x10CC` tests the corresponding Z80 destination slot.
4. PC `0x10D0` skips a nonempty destination.
5. PC `0x10D2` clears the accepted M68K source slot.
6. PC `0x10D6` writes D0.b into the Z80 slot.

Reaching the pre-execution callback at `0x10D6` therefore proves that the source was nonzero,
the destination was empty, and this exact request was accepted for transfer. Sampling D0 and
D1 before this instruction is the last unambiguous boundary before the request becomes Z80
queue state.

The shipped `FixBugs = 0` path counts D1 from 3 through 0. Slot 3 aliases the first byte of
`VoiceTblPtr`; the nominal `SoundQueue.SFX2` source is normally never written. The producer
must record the actual 0-through-3 slot without normalizing it. Consumers must retain and
validate slot 3 as shipped behavior, but must not silently reinterpret it as an ordinary
consumable `Queue0..2` request.

## Why tick 210 does not reveal the request

The current diagnostic comparison first diverges at tick 210, movie row 10412. The reference
shows FM4 under SFX override and a post-admission live SFX pointer of `F839`, which maps to the
`CE` left-ring program. Those are driver outcomes after request transfer and admission. The
leading PSG write `9A` is likewise output, not request identity.

This evidence cannot distinguish whether the M68K transferred raw request `B5`, explicitly
transferred `CE`, or reached the same downstream program through another source-owned route.
It cannot establish the transfer row, slot, or ordering either. The producer must record D0 at
the `0x10D6` boundary. Output, post-frame RAM, pointer identity, row number, and fixture content
must never synthesize or backfill the request stream.

## Existing ABI 4 / Sonic 1 authority pattern

No new native semantic event or ABI revision is required by the currently reviewed design:

- `tools/tracechaser/bizhawk-headless/native/gpgx-audio-observer/README.md:47-52` defines
  action 7 as an exact A7 observation marker sampled between the managed callback and opcode
  execution.
- `tools/tracechaser/bizhawk-headless/native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch`
  records that marker as native event kind 10, value 3, with the exact four-byte A7 payload.
- `tools/tracechaser/bizhawk-headless/src/Core/GpgxHost.cs:326-399` provides pre-operation M68K
  execute callbacks and register reads.
- `tools/tracechaser/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs:818-823`,
  `:1588-1682`, `:2042-2060`, `:2228-2264`, and `:2380-2392` implement the existing Sonic 1
  managed-register/native-marker correlation.
- `tools/tracechaser/bizhawk-headless/src/Audio/S2AudioObserverProfile.cs:384-435` already
  upgrades the Sonic 2 observer configuration to ABI 4.

The safe extension is to reuse that closed pattern for the single reviewed Sonic 2 transfer
site. It must not expose a caller-selectable PC or accept an unreviewed token/action mapping.
If later review rejects the hybrid managed-register/native-marker boundary for Sonic 2, work
must stop: a native D0/D1 event would be a new observer identity family requiring a separately
reviewed ABI, patch, build, provenance, and capability contract. It must not be introduced as a
quiet compatibility change.

One repository-governance prerequisite is also explicit. The pinned TraceChaser
`AGENTS.md:207-232` and mirrored `CLAUDE.md` currently say that only two address-filtered
M68K callback exceptions are permitted. That statement predates the reviewed Sonic 1
complete-audio correlation surface and does not authorize this new Sonic 2 address. Before any
implementation edit, the exact `0x10D6` observation-only exception and its non-mutation,
non-inference, deterministic-unregistration gates must be approved and added to both files in
one policy-compliant change. This audit records the needed change; it does not silently bypass
the current instruction or edit those files itself.

## Current missing surfaces

### TraceChaser producer

The present Sonic 2 surface is service/state/chip-only:

- `tools/tracechaser/bizhawk-headless/src/Audio/GpgxAudioServiceManifest.cs:30-39` accepts
  actions 1 through 4 and has no fixed `sndDriverInput` request-transfer definition.
- `tools/tracechaser/bizhawk-headless/src/Recording/S2CompleteAudioCaptureRunner.cs:168-189`
  creates the stock host, observer, and raw sink but registers no M68K execute callback.
- `tools/tracechaser/bizhawk-headless/src/Recording/S2CompleteAudioRawSink.cs:79-111`
  publishes only post-advance Z80 state and native service/chip events.

The implementation needs:

1. A fixed manifest/profile entry for PC `0x10D6`, opcode `13801009`, the reviewed routine
   identity, and action-7 marker alternatives for every reviewed reachable active-service
   topology. The token map must be closed, immutable, and validated as an exact inventory.
2. Exactly one pre-execution callback at `0x10D6`. It must read D0, D1, and A7, require a
   nonzero D0 byte and canonical D1 word in 0 through 3, and enqueue a bounded pending record.
3. Correlation with the next same-PC native kind-10/value-3 action-7 event whose four-byte
   payload exactly equals A7. Orphans, reordering, duplicates, overflow, cross-frame carry,
   callbacks outside an active row, and unmatched terminal records must fail closed.
4. Power-on observation with publication beginning only at the fixed comparison boundary row
   769. Pre-boundary activity may establish observer state but must not enter the comparison
   inventory.
5. Capability and manifest refreshes that pin the manifest, capability template, harness
   executable, correlator sources, request inventory, request count/digest, native event
   count/digest, occupancy, and terminal evidence. Native core hashes and ABI may remain
   unchanged only if the native patch is unchanged and independently reproduced.
6. Native regression/self-tests proving managed callback, native action-7 marker, and opcode
   execution ordering. These tests are required even when the native patch itself is unchanged.

### Raw schema and extractor

The closed Sonic 2 complete-audio raw format must advance from v2 to v3. Each row needs an
exact ordered `request_transfers` array carrying at least the row/order, raw request byte,
slot, PC, A7 correlation token, native event ordinal, and active service owner. The schema and
sink must reject unknown fields, invalid ranges, duplicate ordering, inconsistent ordinals,
and any request without the exact producer correlation evidence.

The old environment-gated oracle-window capture must not be revived. A closed deterministic
extractor should transform authenticated full-run raw v3 into the existing bounded oracle raw
v2 window `[10150,10900)`, preserving request evidence, source rows, service ordinals, and
identity. It must not derive request values from state, pointers, writes, or the known
divergence.

### OpenGGF consumer and producer

`S2OracleRawStream.Frame` and the comparator's `ReferenceTick` need an authenticated reference
request-transfer representation. Complete-run raw adapters, projectors, and profiles must
validate that representation and classify it as reference observation only. A reference
request is comparison data; it is never a `Decision` and may not call gameplay owners or
drive OpenGGF.

The current `src/main/java/com/openggf/tools/audio/parity/s2/S2OracleEngineCapture.java:62-81`,
`:121-143`, and `:156-184` test seam admits at most one supplied request per tick and calls
`admitSfx` directly. Before authentic request comparison, OpenGGF needs the ROM-shaped M68K
mailbox and Z80 queue behavior: transfer every source-owned accepted request in order, retain
the 0-through-3 slot semantics, and let the source-owned Z80 update decide when `zCycleQueue`
scans, clears, prioritizes, promotes, or rejects it. An independent OpenGGF observer may then
report the equivalent production-owned transfer/admission evidence.

Until that independent producer exists and is authenticated, the reference request stream
must not be connected to `DriverRequest`, and no request-layer `MATCH` may be reported.

## Queue and admission semantics

The `0x10D6` transfer is stimulus delivered to Z80 RAM, not proof that the Z80 admitted or
played it. `zCycleQueue` runs only when `QueueToPlay == 0x80`; it scans `Queue0..2`, clears
entries, applies its priority rules, and may promote a request for `zPlaySoundByIndex` in the
ROM-owned update flow. Multiple M68K transfers may occur before one Z80 queue cycle, and slot 3
is outside the normal three-entry scan.

Consequently, neither the extractor nor Java may collapse arbitrary transfer events into one
request per tick, equate a transfer with playback, or use the first visible downstream program
as the original raw byte. The canonical model must preserve ordered transfer, queue, admission,
and later driver consequences as distinct source-owned stages.

## Authority and provenance limitation

`docs/architecture/validation/audio/2026-09-01-override-resume-reference-limitation.md`
remains authoritative. Task 8's atomic bundle implementation established safe synthetic
publication mechanics; it did not establish fresh native-GPGX observation authority. The
current blockers include absent current-session two-build inputs, the pinned `/usr/bin/ar`
trust-root mismatch, the reviewed capability/source mismatch, and the lack of a fresh
authenticated observer install.

This audit does not repair those blockers. It does not authorize a capture, fixture,
capability re-pin, producer binding, or comparator claim. Any future request evidence remains
non-authoritative until the full fresh-build, installation, provenance, duplicate-capture, and
review gates succeed.

## Bounded TDD and capture sequence

Implementation should proceed in this order and stop at the first failed authority gate:

1. Obtain explicit approval for the fixed observer exception and update TraceChaser `AGENTS.md`
   and `CLAUDE.md` together. Do not touch implementation while their two-exception rule still
   excludes this address.
2. Add failing pure C# tests for the exact hook/opcode identity, D0/D1/A7 sampling, each
   reviewed topology, marker correlation, pending-queue bounds, terminal cleanup, raw v3
   schema, and all negative ordering/range/identity cases.
3. Implement the fixed Sonic 2 observer session using the existing ABI 4 action-7 mechanism;
   do not change the native patch unless review first approves a new observer identity family.
4. Add failing extractor tests for full-run v3 input, the fixed window, preserved request
   evidence, duplicate/ordering failures, and every prohibited inference; then implement the
   closed extractor.
5. Add failing Java raw-adapter, oracle, queue/admission, profile, and authority-guard tests;
   implement the minimum ROM-shaped source-owned path without connecting reference values to
   behavior.
6. Run native self-tests and only the exact named pure C# non-live tests, with empty process
   inventories before and after every Mono invocation.
7. Restore the locked observer trust roots and complete the mandatory two-fresh-build observer
   reproduction. Reconcile capability identity through review, not by loosening validation.
8. Reproduce the managed harness twice and require deterministic executable identity.
9. Run two serial power-on Sonic 2 full captures to distinct absent external paths. Require
   normalized attestations, request streams, native events, terminal state, cutoff frontier,
   and all digests to agree. Do not begin the second run until the first process tree has exited
   and the post-run inventory is empty.
10. Obtain independent review. Only after approval may the fixed window be extracted and the
   reviewed capability/profile digests be installed. No request fixture may be hand-written,
   patched, inferred, or copied from output.

## Safe process supervision for future execution

No process was launched for this audit. A future approved test or capture session must prove an
empty relevant process inventory before and after each invocation:

```bash
ps -eo pid=,comm= | awk '$2=="mono" || $2=="mono-sgen" || $2=="EmuHawk" || \
  $2=="EmuHawk.exe" || $2=="BizHawk" || $2=="GPGX" {print}'
```

Named pure C# tests are the only permitted Mono use before capture authority is restored. A
future capture must use the closed TraceChaser launcher, an authenticated BizHawk 2.11 home,
absolute verified ROM and BK2 paths, the fixed S2 service manifest and capability, and a new
external output path. The closed invocation shape is:

```bash
BIZHAWK_HOME=/absolute/fresh-authenticated/BizHawk-2.11-linux-x64 \
timeout --foreground --kill-after=10s 6h \
  /absolute/TraceChaser/bizhawk-headless/run-complete-audio.sh \
  --complete-audio-game s2 \
  --rom /absolute/Sonic2Rev01.gen \
  --movie /absolute/sonic-2-sonic-tails-complete-emeralds.bk2 \
  --service-manifest /absolute/gpgx-audio-service-manifests-v1.json \
  --capability /absolute/gpgx-audio-capability-v1.json \
  --output /absolute/external/absent/s2-complete-audio-raw-v3.jsonl
```

The launcher must remain the process owner through its `exec mono` boundary and must keep
graphical display access disabled. Any residual Mono, EmuHawk, BizHawk, or GPGX process is a
hard failure before another command may run.

## Decision

The exact pre-consumption request boundary is sufficiently source-closed to implement with the
pinned ABI 4 observer and the existing Sonic 1 correlation architecture. The implementation,
fresh authority reproduction, duplicate captures, independent OpenGGF production evidence,
and comparison are all still pending. Sonic 2 comparison must remain stopped at the request
frontier until those gates close.
