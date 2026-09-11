# Handover Follow-ups Design

## Requirements

### Goals

1. Restore `S2SpecialStageRecorderContractTest` without weakening provenance checks or
   rewriting a valid canonical fixture.
2. Audit persistence on the S3K objects that can influence the current AIZ, HCZ, and MHZ
   complete-run frontiers, and correct any engine lifetime that contradicts the object's
   ROM `RememberState` / `DeleteObject` path.
3. Resolve or rigorously re-characterize the three reported S3K Kosinski-admission
   frontiers at AIZ direct `#35`, HCZ direct `#90`, and MHZ direct `#335`. Fix the
   smallest ROM-owned production defect when causality is demonstrated. AIZ is first
   because it is in the primary release slice; HCZ follows; MHZ remains behind its known
   slot-occupancy-dependent divergence unless new evidence isolates a safe correction.
4. Record a safe disposition for the misleading object-update `frameCounter` parameter
   without creating a wide conflict during active concurrent development.

### Non-goals

- Do not make the hardware timing port create work, choose a producer, prepare a payload,
  renumber an existing submission, or infer gameplay state from trace comparison data.
- Do not accept multiple arbitrary recorder stamps for one canonical S2 fixture.
- Do not sweep every `isPersistent()` implementation. The first pass is route-led and
  limited to objects relevant to the current AIZ, HCZ, and MHZ complete-run paths.
- Do not perform a partial update-parameter rename. A half-renamed hierarchy would be more
  misleading than the current consistent but inaccurate name.
- Do not tune values from the reported comparator field. Each change must be grounded in
  the disassembly or in an engine ownership/lifecycle mismatch demonstrated by measurement.

### Constraints

- Runtime assets remain ROM-backed.
- Shared timing and gameplay code may not gain game, zone, route, trace, or frame-index
  carve-outs.
- Recorded schema-2 hardware timing may only delay readiness of a matching prepared,
  production-submitted job at the recorded service boundary.
- Objects use injected services and gameplay tile edits remain behind the mutation surface.
- JDK 21 is required. Complete-run tests run in isolated Maven invocations because their
  fixture sizes can exhaust a shared Surefire fork.
- Work is developed on `bugfix/ai-handover-followups` in an isolated worktree. The main
  workspace remains on `develop` and its unrelated files are preserved.

### Acceptance criteria

- `S2SpecialStageRecorderContractTest` passes while separately pinning Lua source version
  `1.4-s2ss` and canonical native fixture version `1.4-s2ss-native`.
- The concrete persistence shortlist below has a ROM-grounded classification. AIZ Draw
  Bridge is corrected with a focused manager-level unload/respawn test. MHZ Swing Vine is
  first characterized under a grabbed/off-screen camera case, then corrected if the
  reachable engine behavior contradicts the unconditional ROM range tail.
- For each S3K route, either the named terminal admission advances/disappears with a
  ROM-grounded production fix, or the unchanged edge is recorded as a negative result with
  exact causal/independence evidence, report counts, and the next safe owner. Any change
  must not regress the other complete-run segments, the AIZ release-slice gate, rewind
  coverage, or hardware-authority guards.
- The hardware timing authority tests remain green. The sole generalized replay
  clarification is that a compiled current-row `PRE_MAIN_LOOP` completion may be exposed
  from a suppressed held-counter row without running production service or gameplay. It
  does not loosen identity, preparation, producer, or boundary requirements.
- The broad `frameCounter` rename is either completed atomically with hierarchy-wide
  verification or explicitly deferred with measured blast radius and a quiet-tree gate.

### Assumptions and risks

- The handover named commit `220e0bc35`; current `develop` and `origin/develop` are
  `2c64e09d4`. The later commits are visual trace integrations and do not change the S3K
  Kos timing contract, but all baselines and delivery checks use the actual current head.
- Complete-run failures are high-latency and can cascade. Reports must be read from their
  earliest non-cascading group, not only from the terminal exception.
- Persistence errors can change dynamic slot allocation far downstream. Route relevance
  therefore includes spawners, invisible controllers, and self-managed off-screen objects,
  but event-owned controllers must not be forced into a placed-object `RememberState`
  model.
- There are hundreds of update implementations and active concurrent worktrees. The rename
  has high conflict risk and no runtime benefit, so the safe default for this campaign is
  deferral.

## Exploration Synthesis

### S2 special-stage recorder contract

Commit `bceb299d8c28bfeb1dca6ffe907ffb5156870de8` published the audited native S2
special-stage fixture. It changed metadata from `1.4-s2ss` to
`1.4-s2ss-native`, added the native dynamic-art auxiliary capability, and regenerated
payloads, but omitted the artifact assertion in `S2SpecialStageRecorderContractTest`.
The native writer and behavior specification require the `-native` stamp. The Lua source
still correctly declares `1.4-s2ss` and cannot reproduce the current enriched auxiliary
stream. The two assertions describe two different recorder implementations.

Decision evidence rules out both alternatives: accepting either stamp would weaken
canonical provenance, while relabeling or re-recording the fixture would misrepresent the
audited native capture.

### Persistence

`ObjectInstance.isPersistent()` bypasses generic out-of-range removal in
`ObjectManager`. A false positive can therefore retain a dynamic slot indefinitely, as
the corrected Sonic 1 LZ conveyor-wheel case demonstrated. The audit distinguishes:

- placed objects whose ROM tail calls `RememberState` or `DeleteObject`;
- objects with accurate self-managed render/bounds deletion, which may legitimately return
  true to bypass an earlier generic cull;
- event/session-owned controllers that are not placed-object `RememberState` equivalents;
- children with explicit touch/render retirement paths.

The reproducible inclusion rule is: persistent concrete classes instantiated by the AIZ,
HCZ, or MHZ route from segment start through its current frontier, plus their reachable
spawned children and fixed/event-owned controllers. Boss-only classes beyond the frontier,
uninstantiated registry entries, and generic classes not reached by these route placements
are excluded. The resulting shortlist is AIZ Draw Bridge, Collapsing Log Bridge and pieces,
generic S3K Collapsing Platform and fragments, AIZ background-tree spawner/tree, Cork Floor
fragments, Breakable Wall fragments, HCZ Conveyor Belt, Breakable-Bar debris, Hand Launcher
arm, Twisting Loop, MHZ Swing Vine, and the MHZ pollen spawner.

The audit found two candidates. AIZ Draw Bridge is a high-confidence mismatch: Java keeps
every phase persistent with no self-cull. Its normal/wait operations apply the saved-anchor
coarse-X tail, delete referenced children, clear the respawn bit, and delete the root
(`sonic3k.asm:59649-59676`), while the triggered collapse operation deliberately skips that
tail during its `$0E` countdown and self-deletes at expiry (`59769-59791`). MHZ Swing Vine
remains persistent while grabbed, but its ROM root has no grabbed exemption from the
equivalent child-chain/root range tail (`sonic3k.asm:47164-47192`). The remaining shortlisted
overrides are justified by exact self-managed range/render deletion, parent-child coupling,
or fixed/event ownership.

### S3K hardware timing

Fresh JDK 21 replays reproduce all three terminal errors with gapless, advancing ordinals:

| Segment | Terminal recorded edge | Earlier first comparator divergence |
|---|---|---|
| AIZ | direct `#35`, raw 6346, `c3e8ddd...` | frame 1106, engine-only AIZ main-level Kos work |
| HCZ | direct `#90`, raw 3341, `66961069...` in the fresh isolated method run | frame 3253, Tails motion; causal relationship to the later edge is unproven |
| MHZ | direct `#335`, raw 7221, `3c96d8b...` | frame 3420, slot-dependent bouncing-ring pickup |

Later historical runs have advanced HCZ as far as title-card work near raw 10391, which
further demonstrates that its terminal edge follows producer/lifecycle corrections rather
than a stuck queue. The exact HCZ terminal ordinal depends on the replay baseline and test
method selected; the invariant is that the engine has no matching production submission
after an earlier divergence.

The timing service is fail-closed: it admits only an already-prepared FIFO head with the
recorded kind, ordinal, fingerprint, and boundary. Earlier matching admissions succeed.
The August 1 loop-tail migration correctly changed production and enum order to
`VINT_SERVICE`, `POST_OBJECTS`, `PRE_MAIN_LOOP`, but left one replay-port test and two
contract sentences in the former PRE-before-POST order. Those stale expectations are
corrected here; no runtime boundary order changes.
Producer mismatches must not be repaired in this layer. AIZ triage found one narrower replay
representation seam, however: raw 6346 is a lag row whose ROM CPU prefix reaches
`Process_Kos_Queue` before `Wait_VSync`, and the schema-2 ledger records direct `#35` at
`PRE_MAIN_LOOP`; the replay skips the whole gameplay tick, so no production boundary callback
consumes the otherwise exact, prepared engine submission before raw 6347 rejects it. The
permitted correction belongs to the shared suppressed-row replay lifecycle. When the
compiled schedule has a `PRE_MAIN_LOOP` completion on the current held-counter raw row,
`TraceSuppressedRowClosure` exposes that one boundary to
`TraceHardwareTimingBoundaryObserver` after the row's VInt closure. It does not execute a
second `HardwareBoundaryDispatch`, production decompressor service, main loop, object scan,
or producer. After an exact admission, the closure runs only
`RuntimeArtCoordinator.afterTimingService(PRE_MAIN_LOOP)`, the missing production
post-service half that retires the now-ready direct FIFO head. It does not run the
coordinator pre-step or timing service, so it cannot advance preparation or create work;
the KosM parent observes the retired child through its ordinary next `POST_OBJECTS` step.
After VInt, production's last-service marker correctly reads `VINT_SERVICE`,
so the ordinary completion-authority operation cannot certify the earlier loop-tail edge.
The authority therefore gains one separate suppressed-row operation, callable only by the
replay port for a compiled current-raw `PRE_MAIN_LOOP` head. It bypasses only that stale
last-service equality and reuses the exact pending-head, kind, ordinal, fingerprint,
preparation, release, ordering, deduplication, and rewind checks. Source guards confine the
operation to the port and the port entry to the stateless observer. Raw-time advancement
alone remains non-authoritative and stale edges still fail. This changes only when real,
already-prepared submitted work becomes ready and reads no lag, physics, auxiliary, game,
zone, route, or frame-specific comparison state.

HCZ `#90` and MHZ `#335` do not have matching engine submissions and therefore remain
producer/lifecycle failures; the current-row suppressed-boundary path must reject them
unchanged. An earlier comparator divergence is not automatically causal: an
independent, exactly identified production edge may still be corrected while earlier
physics differences remain. Instrumentation must establish causality or independence
before selecting an owner.

The route-specific upstream owners are:

- AIZ: the repeated post-reload Monkey Dude job is real, already submitted, prepared, and
  fingerprint/ordinal exact. The terminal is the collapsed CPU-prefix timing seam above;
  intro/title-card and seamless-transition mismatches remain comparator frontiers after the
  admission error is removed, but they are not prerequisites for admitting `#35`. The
  implemented closure admits direct `#35`, retires its real FIFO head, and then admits the
  dependent module `#15` on the next ordinary `POST_OBJECTS` step. Replay reaches raw 6351,
  where fixture module `#16` is stamped `VINT_SERVICE` on another held-counter row while
  the production parent is still unprepared. The authority correctly rejects it. The
  native classifier in `HardwareTimingEventEngine.ObserveFrameEnd` assigns duplicate
  `Level_frame_counter` module retirements to `vint_service`; the fixture was published by
  `bceb299d8`/regenerated byte-identically by `8a6313bb3` before `ddaf8e152` changed the
  engine's loop-tail phase model. The next owner is an audited recorder
  observation-row/service-row attribution review. A stale attribution requires correction
  and separately approved re-publication; if the current stamp is validated, any broader
  partial-CPU-prefix representation requires a separate design review. This branch must not
  guess between those outcomes or broaden timing authority.
- HCZ: direct `#90` is the Stars3 child of module `28a69b8f...`, produced by the StarPost
  bonus-star art path at raw 3341/3342. Measure whether the frame-3253 Tails divergence
  changes StarPost contact/production. Select among sidekick, earlier water-wall/geyser
  lifecycle, and StarPost ownership only after that measurement. Later historical runs
  also reach results/title-card work, but that is not the current `#90` owner.
- MHZ: dynamic slot occupancy changes the ROM's slot-phased bouncing-ring floor probe and
  prevents the later repeated special-stage-entry-ring explosion-art submission. There is
  no safe object-local change yet for the first divergence.

### Update parameter name

> **Superseded 2026-08-02:** the dedicated quiet-tree work is now specified in
> [the object V-int run-count terminology design](../2026-08-02-object-vint-run-count-terminology.md).
> The deferral below records the constraint on the original mixed-workstream branch.

`ObjectExecutionController` passes `ObjectManager.vblaCounter()` to
`ObjectInstance.update`, but the interface and approximately 590 update implementations
name the parameter `frameCounter`. `ObjectManager` also has a distinct generic
`frameCounter`, so the name actively obscures the V-int run-count de-phasing invariant.
Because the rename spans the hierarchy and current sessions are concurrently editing many
of those files, it is deferred from this branch. The eventual change should use a single
name such as `vIntRunCount` atomically across interface, implementations, tests, and local
helpers, followed by a full compile and suite run.

## Architecture Decision

Use a provenance correction, a bounded ROM-lifetime audit, and trace-frontier-first S3K
fixes. Preserve `HardwareTimingService` and its production authority. The only timing replay
change allowed here is the fail-closed current-row suppressed-boundary exposure above; do
not perform the update-parameter rename in this active tree.

This keeps ownership at the smallest accurate boundary:

- S2 canonical metadata expectations remain in the recorder contract test.
- Persistence corrections live in the affected object and its focused lifetime test.
- S3K changes remain at the smallest ROM-owned production/lifecycle boundary, including
  event or object state, execution ordering, placement/cursor lifetime, sidekick control,
  or transition sequencing as evidence requires. The shared hardware timing port and its
  narrowly confined suppressed-row authority continue to control only when matching,
  already-prepared production work becomes ready.
- The semantic rename is reserved for a dedicated quiet-tree branch because it is a broad
  source-compatibility edit with no behavioral coupling to the other work.

Alternatives rejected:

1. Accepting both S2 stamps: this turns a canonical artifact check into a permissive
   compatibility check and can conceal an incorrectly published fixture.
2. Re-recording with Lua: the Lua recorder lacks the fixture's native dynamic-art stream.
3. Synthesizing a missing S3K job at an expected edge: recorded timing would decide what
   happens, violating the authority boundary.
4. Synthesizing, moving, or identity-loosening a terminal edge in the timing layer: timing
   authority cannot replace the exact production owner. The accepted AIZ correction only
   exposes the fixture's exact current-row boundary to an already-prepared matching job.
5. Opportunistic partial parameter rename: it increases conceptual inconsistency and merge
   conflicts.

## Feature Design

### Workstream A: S2 provenance

Change the committed-fixture assertion to the exact native stamp. Preserve the exact Lua
source declaration assertion. Verify the Java contract and the native standalone
special-stage publication test.

### Workstream B: route-led persistence audit

Publish the concrete shortlist above as an audit table with object identity, ownership
type, Java lifetime behavior, ROM tail/cull routine, and disposition. For each candidate
mismatch:

1. demonstrate the current slot-retention behavior in a focused test;
2. change only the object's persistence or self-managed retirement behavior;
3. verify rewind recreation/state coverage;
4. rerun the relevant route replay and record whether the first frontier changes.

For AIZ Draw Bridge, normal/wait phases use a custom fixed-native `$280` coarse-X predicate
with the saved pivot rather than the moving bridge position; the triggered collapse phase
stays persistent only through its timer-owned self-delete. Range evaluation must happen
after the bridge routine for this object (or preserve exactly equivalent ordering):
`AIZDrawBridge_WaitCollapseTrigger` consumes the button flag and enters `loc_2B452` before
the ROM could reach the normal range tail. The regression must trigger collapse while the
bridge is already beyond `$280`, prove that same dispatch survives, and then prove that the
`$0E` countdown performs its own timed delete. For MHZ Swing Vine, a
grabbed/off-screen characterization test is the gate because P1 swinging also forces camera
tracking toward the anchor.

### Workstream C: S3K trace frontiers

Treat each trace as a measured pipeline:

1. Reproduce in an isolated Maven fork and save the report/context.
2. Identify the earliest non-cascading divergence and the terminal production owner;
   measure whether they are causal or independent.
3. Instrument the owner and compare actual rows/state transitions against disassembly.
4. Add a focused failing unit or lifecycle test before changing production code.
5. Implement the smallest ROM-grounded owner correction.
6. Rerun the trace and all three segment checks; update the frontier log with exact counts,
   frames, commands, and commit/worktree context.

AIZ is the first implementation target. HCZ begins only after remeasuring on the AIZ
candidate. MHZ may still receive an independent, ROM-grounded producer fix while its
slot-dependent comparator error remains, but no trace-driven or fitted substitute is
permitted. Each route closes with either an advanced/removed named edge or a documented
negative result meeting the acceptance criterion above.

The AIZ route therefore closes this branch with a bounded positive and a bounded negative:
direct `#35` and its dependent module `#15` advance through the production queues, while
module `#16` remains a fail-closed recorder/fixture-contract frontier. No fixture payload is
edited under this workstream.

### Workstream D: semantic rename disposition

No source rename occurs here. Record the measured hierarchy size and recommended atomic
name in the implementation plan. A future dedicated branch must begin from a quiet tree,
mechanically rename the interface and all implementations, remove redundant local
`resolveVIntRunCount` bridges where justified, and compile before behavior tests.

### Integration-suite isolation blocker

The first development full-suite run exposed an order-sensitive failure in
`Sonic1SpecialStageResultsScreenTest`. The result screen's readiness contract polls the
session-owned `Sonic1PlcService`, while this test currently resets only game-state and
session facades. A reused Surefire fork can therefore leave an unrelated PLC busy and
freeze the result card for the test's entire frame budget. The unchanged baseline and the
development branch both pass all four methods when the class runs alone, and no production
change in this delivery touches Sonic 1 results behavior.

Treat this as test isolation, not a production timing change: install the repository's
standard `SingletonResetExtension` and `@FullReset` at class scope. The full reset is
required because the lighter per-test profile does not clear `Sonic1PlcService`; it rebuilds
the module boundary instead of mutating a potentially inherited service. Retain the existing
per-test assertions. Remove the test's now-stale ambient-setup exemption from
`TestSingletonLifecycleGuard`, whose scanner treats the class-scoped extension as safe, and
run that guard before the exact development full suite. Do not change result-card timing,
PLC readiness semantics, or production reset ownership to compensate for fork-local test
contamination.

The first post-merge suite exposed the same class of ambient-state dependency in
`TestS3kSignpostInstance#fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity`.
Its local services intentionally omit terrain, but production `ObjectTerrainUtils` resolves
the level through `GameServices.levelOrNull()`. In a reused fork the class can inherit a
loaded level, land the signpost during the falling-only contract, and zero `yVel`; the full
17-method class passes alone. Install `SingletonResetExtension` plus `@FullReset` at class
scope so the falling contract begins without inherited terrain. Keep production collision,
signpost behavior, and the existing assertion unchanged.

### Verification and rollback

Focused tests precede route replays. Hardware timing authority, rewind coverage, the four
S3K keep-green gates, and all three affected complete-run tests form the regression set.
Any candidate that worsens a previously passing frame or alters an unrelated segment is
reverted at the candidate level rather than compensated elsewhere. No fixture is modified
to make an engine change pass.
