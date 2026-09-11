# Focused trace diagnostics

Use these sections only for the corresponding failure family. Paths and symbols
are search entrypoints; inspect their current implementations before changing them.

## Bootstrap and visual run boundaries

For v5 fixtures advertising `native_prelude_bootstrap`,
`TraceBinder.compareBootstrapFrame0` compares the engine's naturally built state
with prelude snapshots. A mismatch is evidence to fix native prelude execution,
not permission to seed history, CPU, RNG, or object snapshots from the fixture.

`VisualRunReplayHarness` exercises `TraceSessionLauncher` and the production
`TraceRunFrameDriver` path headlessly. Use it for visual-session aborts, pauses,
transition softlocks, and unpublished dynamic-art rows; a green
`AbstractRunChainTest` can miss these because it uses a separate driver loop.
Choose `stopAfterSegment(n)` for the actual frontier and tear down the harness.

## Physics, standing, and object slots

Compare centre coordinates and subpixel carry before changing collision math.
`EngineDiagnostics` reports `ride`, `standsnap`, `onSlot`, and `sub`; standing
snapshots and the live on-object status bit can legitimately differ across phases.
ROM probe offsets can be fixed pixels rather than sprite radii: read the probe
routine rather than substituting the current rolling radius.

For `obj_sNN_slot` or `eng-expected-onObj ... missing`, find the first occupancy
difference from level start, before the later ridden-object symptom. Inspect
`SlotAllocator` active **and reserved** slots: visible objects alone omit reserved
children. Use the production replay bootstrap and compare equivalent sampling
boundaries; a mid-load VBlank slot dump is not an end-of-pass observation.

Identical occupancy and the same allocation rule imply the same slot choice.
Check spawn/free timing and allocation semantics: lowest-free versus after-parent,
parent-slot reuse, in-place replacement, child counts (`dbf` is count+1),
respawn-tracked remember bits, and parent-owned group deletion. Do not label an
unexplained slot mismatch “RAM-gated” without observing the lifetime event; when
aux cannot resolve it, use TraceChaser's PC-execute/RAM probes. Real spawned
children need rewind coverage; do not baseline a gap merely to silence the guard.

For input alignment failures, compare the BK2 input log and `bk2_frame_offset`
first. ROM `Ctrl_1_Held` can be stale on lag/long V-int paths even when movie
input is correct. Use the native recorder and its documented repair facilities;
a historical Lua recorder version is not a runtime compatibility selector.

## Queue and dynamic-art evidence

Audited captures declare `load_queue_state_per_frame`; player-art lifecycle
captures also declare `dynamic_art_transfer_state_per_frame`. Use `plc-system`
and, for S3K queue ownership, `s3k-plc-system`.

| Report family | Inspect |
| --- | --- |
| `queue.s1_nemesis_plc.*`, `queue.s2_nemesis_plc.*` | Physical PLC membership, order, active preparation, remaining work |
| `queue.s3k_kos_direct.*` | Direct Kosinski jobs |
| `queue.s3k_kos_module.*` | KosM parent lifecycle |
| `dynamic_art.*` | Ordered requests, completions, outstanding transfer IDs, terminal forwarding |
| `run_gap.*` | Transition-gap ledger and destination admission boundary |

These fields are zero-tolerance comparison data. An empty reserved
`service_observations` array is not proof that no service ran. Infer service
from valid membership/preparation/remaining-work transitions. A timing-port
admission error is distinct from a comparator mismatch: inspect job identity
and boundary matching before downstream physics or events.

For transition gaps, `deliverySerial` is not the gap ledger: it advances when
rows publish inside an open segment. Inspect
`DynamicArtLifecycleService.gapTransitions` and `TraceRunDynamicArtGapJournal`.
The ledger is compared at destination admission before that row's body runs,
and the shared movie clock still names the gap's last row at admission.
Confirm whether an explicitly held preparation tail must settle there; an
unconditional flush can invent transfers. Gap rows do not necessarily execute
the production VBlank service path. Read the actual driver before assuming they do.

Derive quantized service from the ROM. S1 `ProcessPLC_9Tiles` and
`ProcessPLC_3Tiles` set different tile budgets; finishing an entry loses the
unused budget when `ProcessPLC_ShiftCue` returns. This is per-entry rounding,
not rounding the total. S3K direct Kosinski in-progress state can instead
represent subframe execution. These mechanisms require different treatment.

## Sampling, recorder defects, and comparison changes

A recorded VBlank can split `RunObjects`, sampling one player before and one
after the pass. When available, an atomic `run_objects_end` observation supports
comparison normalization; do not alter gameplay to imitate interrupt placement.

Determine who produces each compared quantity. Some load-window lengths are
harness scheduling rather than engine execution. A collector can also report
its own ledger rather than a hardware write. Inspect the observer and the ROM
branch before treating either as ground truth. A three-way disagreement among
engine, ROM, and recorder requires fixing or documenting each relevant defect.

Any comparison projection must have a precise evidence-backed predicate, state
what coverage it removes, and name its retirement condition if bridging a
recorder defect. Test that genuine mismatches outside the predicate still fail;
mutate relevant identity/order/value fields to verify the comparator remains
sensitive. Do not broaden exclusions to absorb a new failure.

## Execution-order traps

A flag written inside an object pass but read before the pass has one-pass
visibility. Latch at the ROM read boundary and check all writers/consumers;
an arbitrary one-frame delay is not the same mechanism. Similarly, if an
object's movement follows track phase while collision reads player state,
changing its update count can repair one coupling and permanently break the
other. Align the clocks before tuning object cadence.

When corrected behavior worsens a trace, check for cancelling defects on the
baseline. Record unchanged or worsened measurements honestly and fix new
regressions before integration. Compare experimental error profiles with prior
attempts so renamed hypotheses do not repeat the same failed change.
