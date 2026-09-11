# Frame-level load-queue comparison

Date: 2026-07-29

## Problem

Trace failures currently expose downstream symptoms such as a late object routine,
music transition, or event flag without exposing the load queue that gated that
consumer. This encourages speculative fixes to unrelated gameplay code. S1 and S2 now
have native Nemesis PLC service queues, while S3K has direct and moduled Kosinski
queues, but their state is not represented in ordinary per-frame divergence fields.

The runtime timing profile is not the missing mechanism. S1/S2 PLC progress is already
predicted exactly from ROM submissions, preparation boundaries, and VBlank pattern
budgets. Adding a second profiled delay would double-count work. The missing mechanism
is comparison-only observability.

## Decision

Add a normalized, immutable `QueueDiagnosticSnapshot` model shared by recorders,
production queue owners, and the trace comparator. New recordings emit one
`load_queue_state` auxiliary event per represented queue at every stored physics
row; replay merges it only on rows selected by the existing comparison policy.
Replay captures the engine's queue snapshots after the matching production frame and
adds their normalized fields to the ordinary `FrameComparison`.

The fields use zero tolerance and participate in the same error/frontier handling as
position, velocity, object, and auxiliary comparisons. Existing traces without the
declared metadata capability skip queue comparison and remain compatible.

Trace queue data is comparison-only. It cannot submit, prepare, service, complete,
clear, restore, or otherwise mutate an engine queue.

## Normalized model

Each queue snapshot contains:

- `kind`: stable wire identity (`s1_nemesis_plc`, `s2_nemesis_plc`,
  `s3k_kos_direct`, or `s3k_kos_module`);
- `busy`: whether active or waiting work exists;
- `prepared`: whether the physical head is armed/has its native decoder or
  coordinator state installed for service, not whether its work is complete;
- `activeSource`: ROM source address only when the per-kind projection exposes
  stable active identity, otherwise `-1`;
- `activeDestination`: destination tile/VRAM identity only when the per-kind
  projection exposes stable active identity, otherwise `-1`;
- `activeTotalWork`: immutable total work only when the per-kind projection
  exposes the same stable value on both sides, otherwise `-1`;
- `activeRemainingWork`: remaining native work when the same value is observable
  on both sides, otherwise `-1`;
- `queuedFingerprints`: ordered stable fingerprints of waiting descriptors;
- `serviceObservations`: reserved ordered boundary/phase entries. Version 1
  emits an empty list because these sub-frame calls are not stably observable
  at the shared end-frame recorder boundary.

The stable descriptor fingerprint is lowercase SHA-256 over this canonical byte
sequence:

```text
4 bytes  ASCII "OQDF"
1 byte   format version = 1
1 byte   kind id: 1=S1 PLC, 2=S2 PLC, 3=S3K direct, 4=S3K module
4 bytes  unsigned source, big-endian
4 bytes  unsigned normalized destination, big-endian
1 byte   total-present flag, 0 or 1
4 bytes  unsigned total work, big-endian, present only when flag=1
```

Source is always the ROM byte address. Destination normalization is:

- S1/S2 PLC: unsigned pattern/tile index, matching `PlcEntry.tileIndex()`;
- S3K direct: unsigned destination byte address, matching
  `S3kKosDecompressionDescriptor.destinationAddress()`;
- S3K module: unsigned destination byte address, matching
  `S3kKosModuleDescriptor.destinationAddress()` after its pattern-to-byte conversion.

Total is present for immutable S1/S2 and S3K module waiting-descriptor
fingerprints and absent for S3K direct. Prepared S1/S2 and KosM active totals
are sentinel `-1`. The encoding excludes caller, zone, frame, trace identity, ordinal, and
remaining work. Java and C# tests share literal golden input/digest vectors for all four
kinds; neither implementation computes its expected digest with the production helper.

Absent active fields use `-1`, not `null`, so JSON and report rendering remain stable.
An idle queue has `busy=false`, `prepared=false`, active numeric fields `-1`, and an
empty queued fingerprint list.

### Per-kind projection

| Kind | Physical membership and order | Active/prepared | Work units | Retirement and ready/unclaimed |
|---|---|---|---|---|
| `s1_nemesis_plc` | Nonzero retail six-byte PLC slots in slot order. When `PatternsLeft != 0`, retail slot zero is the mutable decoder workspace and is excluded from waiting fingerprints; immutable slots behind it remain ordered fingerprints. | Prepared exactly when `PatternsLeft != 0` / the engine active entry exists. Prepared active source, destination, and total are `-1`: `RunPLC` overwrites the source with its decoder cursor and `ProcessPLC` advances the destination, so reporting the original descriptor from an end-frame sample would be false. | Remaining is `PatternsLeft` / engine remaining patterns. Prepared total is deliberately `-1`. An unprepared head remains represented by the first ordered fingerprint. | Native service pops/clears the active entry. There is no ready-but-unclaimed state. |
| `s2_nemesis_plc` | Same normalization as S1 over the S2 buffer and engine queue. | Same as S1. | Same as S1. | Same as S1. |
| `s3k_kos_direct` | Only retail `Nem_decomp_queue` physical slots / engine `physicalEntries`, in FIFO order. Retained timing jobs are excluded after physical retirement. | The first physical slot is active. Prepared is retail queue-count bit 15: a descriptor appended at `POST_OBJECTS` remains unprepared until the next `PRE_MAIN_LOOP`. The engine tracks this physical arming edge separately from decoder construction. | Total and remaining are `-1`; private decoder progress is not a stable retail RAM metric. | A ready job retires at `PRE_MAIN_LOOP`. Ready-but-unclaimed jobs remain claimable but report neither busy nor queued. |
| `s3k_kos_module` | Retail physical KosM parents / engine incomplete, not-yet-complete module-parent jobs in ordinal order. Direct children appear only in the direct snapshot. Once processing begins, retail source/destination are mutable module cursors, so prepared active identity and total are masked to `-1`; immutable waiting parents remain fingerprints. | The first parent is active. Prepared means its module coordinator state was installed by `Process_Kos_Module_Queue_Init`, observed as a nonzero low-seven-bit module count. Bit 7 means a child decompression is in progress and is not the prepared predicate. | Remaining is the low seven bits of `Kos_modules_left` / engine total minus completed modules. Prepared total is `-1`. | The parent retires when coordination marks it complete at `POST_OBJECTS`. A ready-but-unclaimed parent is not physically busy or queued. |

S1/S2 enforce three canonical shapes. Idle has `busy=false`, `prepared=false`,
all active fields `-1`, and no fingerprints. An unprepared nonempty queue has
`busy=true`, `prepared=false`, all active fields `-1`, and includes its head as
the first waiting fingerprint. A prepared queue has `busy=true`, `prepared=true`,
source/destination/total `-1`, remaining equal to positive `PatternsLeft`, and
fingerprints only for immutable entries behind the workspace. The engine masks
its internally retained active descriptor to these same sentinels before
comparison.

S3K KosM uses the analogous stable shapes. Idle is fully canonical idle. A
prepared parent has `busy=true`, `prepared=true`, source/destination/total `-1`,
remaining equal to `Kos_modules_left & 0x7F`, and fingerprints only for immutable
parents behind it. Before preparation, all parents are waiting fingerprints and
active fields are `-1`. The engine masks its retained active parent identity and
total identically; neither recorder nor engine diagnostics reconstruct identity
from mutable module cursors.

The S1/S2 prepared head intentionally reports only observable stable lifecycle state:
busy, prepared, remaining work, and waiting fingerprints. This
avoids a recorder-owned identity latch or cursor guessing: frame-boundary polling cannot
reliably observe a descriptor before same-frame `RunPLC` preparation, and capture may
begin mid-job. S3K direct progress likewise remains unreported rather than translating
private host-decoder state into a false retail metric.

## Canonical sampling boundary

One event set represents `END_OF_LOGICAL_FRAME`: after every service, retirement,
object/event scan, and after-loop preparation belonging to the represented frame, and
before the next frame's input/VBlank admission.

For S1/S2 this is after the selected VBlank PLC service and optional
`RunPLC`/`RunPLC_RAM` preparation. For S3K it is after `PRE_MAIN_LOOP` direct service
and retirement, the object/event scan, and `POST_OBJECTS` module coordination and
retirement. The native recorder samples after `FrameAdvance` returns the corresponding
physics row. Replay captures after the matching production step and before comparing
that row.

Version 1 always emits empty `serviceObservations`. A non-empty array is a
fixture/schema error rejected during parsing, before replay; publishing
observations in a future format requires an explicit event/capability schema
bump. S1/S2 VBlank selection has
already been consumed or cleared by the end-frame boundary, while S3K lag and
advance-only rows may not execute PRE/POST boundaries. Guessing from game mode
or retaining a previous latch would create false frontiers. Remaining work and
physical membership still expose whether service and retirement occurred.

## Ownership

`QueueDiagnosticSnapshot`, the reserved observation schema, and fingerprint encoding live under
`com.openggf.game.resources`. One `QueueDiagnosticsProvider` interface exposes
`captureQueueDiagnostics()` and accepts no arguments. `PlcLifecycleService` and
`RuntimeArtCoordinator` extend it with default empty-list implementations. S1/S2 PLC
services and `S3kRuntimeArtCoordinator` override it.

`GameplayModeContext.captureQueueDiagnostics()` composes the two providers in stable
kind order, rejects duplicates, and returns an immutable list. Shared session and trace
code use only this method. Snapshots accept no recorded input.

## Recorder contract

The native recorder reads the retail queue RAM after the same emulated frame boundary
used for the corresponding physics row:

- S1/S2: `PatternsLeft` and immutable waiting six-byte PLC slots. Prepared state
  is derived only from `PatternsLeft != 0`; no handler, phase, budget, or
  diagnostic latch is read or inferred. A prepared head's
  source/destination/total use `-1` because the retail fields are mutable decoder
  workspace, not the original descriptor;
- S3K direct: immutable physical queue head and waiting entries, using the
  already reviewed hardware-timing address profile;
- S3K KosM: remaining module state plus immutable waiting parents. A prepared
  parent's mutable source/destination and original total normalize to `-1`.

Each game's metadata declares `load_queue_state_per_frame` only when every stored
physics row carries exactly the complete game set:

- S1: `s1_nemesis_plc`;
- S2: `s2_nemesis_plc`;
- S3K: `s3k_kos_direct` and `s3k_kos_module`.

The publication domain is every `TraceFrame.frame()` value in the segment's
`physics.csv`, including prefix and lag rows; segment gaps are separate trace
directories and numbering remains segment-local. Replay merges queue fields only
on rows where the existing phase policy compares gameplay state. Requiring a
complete event set for every stored row keeps metadata validation independent of
runtime phase classification and leaves prefix events available as diagnostic context.

`TraceData.validateAdvertisedLoadQueueStates(...)` indexes `(frame, kind)` before replay
over the stored physics-row domain. It rejects duplicates, missing kinds,
extra/unknown kinds, events outside the trace frame domain, and malformed snapshots.
Without the capability, events remain displayable diagnostic text but do not affect
pass/fail compatibility.

S1/S2 Lua execute-hook probes remain diagnostic evidence tools and are not fixture
inputs. The native recorder samples stable RAM at the frame boundary; execute hooks are
used only to investigate a sub-frame mismatch that the per-frame comparison identifies.

## Comparison and reporting

`TraceBinder.compareLoadQueues` merges fields into the already captured frame:

```text
queue.s1_nemesis_plc.busy
queue.s1_nemesis_plc.prepared
queue.s1_nemesis_plc.active_source
queue.s1_nemesis_plc.active_destination
queue.s1_nemesis_plc.total_work
queue.s1_nemesis_plc.remaining_work
queue.s1_nemesis_plc.queued_fingerprints
queue.s1_nemesis_plc.service_observations
```

Missing or extra queues are explicit errors. Ordered fingerprints are rendered as a
compact comma-separated list. The first mismatching queue field therefore becomes the
trace frontier before its downstream gameplay symptom.

The report also adds a compact queue summary to ROM and engine diagnostics around the
context window, but these strings are explanatory only; pass/fail comes from typed
fields.

## Replay and profile isolation

- Queue events are parsed only by trace comparison classes.
- Production queue packages do not import `TraceData`, `TraceEvent`, or replay types.
- S1/S2 native queues remain structurally serviced in `NONE`, `PROFILED`, and replay.
- S3K recorded hardware authority continues controlling represented replay jobs; the
  per-frame snapshot independently verifies the resulting physical queue state.
- Normal-play profile manifests do not consume queue diagnostic events.
- Rewind restores production queue snapshots only; recorded diagnostic snapshots never
  participate in rewind.

Source guards enforce the dependency direction and forbid comparison code from calling
queue mutation methods.

## Agent guidance

The cross-game PLC and trace-replay debugging skills must teach the new diagnostic
workflow. Before changing an object, event, music trigger, or presentation countdown
near a divergence, an agent checks the earliest `queue.*` mismatch and classifies it as
submission, ordering, preparation, remaining-work, or retirement drift. A matching
queue snapshot is evidence that the downstream owner should be investigated; a
mismatching snapshot makes the queue frontier the primary target.

Skill changes are mirrored byte-for-byte between `.agents/skills/` and
`.claude/skills/`. If general agent guidance in `AGENTS.md` is changed, the same change
is made to `CLAUDE.md` in the same commit.

## Compatibility and publication

No committed trace is rewritten merely to make current tests consume the new fields.
Recorder schema tests and synthetic Java fixtures establish the contract first.
Subsequent deliberate trace regeneration opts into the capability and must follow the
existing fixture publication policy, compression guard, and frontier-log obligations.

## Verification

Tests cover:

- normalized fingerprints and defensive immutability;
- S1/S2 active, queued, prepared, and remaining-work projection;
- S3K direct/module projection;
- strict event parsing and metadata capability completeness;
- legacy trace omission;
- exact match, every individual field mismatch, missing queue, and extra queue;
- ordinary and S3K replay loops invoking comparison at the correct frame;
- divergence JSON/context rendering;
- trace-to-gameplay dependency and mutation guards;
- rewind leaving diagnostics observational;
- native recorder output and metadata for all three games.
- mirrored PLC and trace-replay skill guidance and its mirror guard.
