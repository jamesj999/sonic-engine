# Sonic 1 and Sonic 2 PLC service queues

Date: 2026-07-28

## Summary

Sonic 1 and Sonic 2 both retain Nemesis-compressed Pattern Load Cue (PLC) work
across frames. Their ordinary loops can continue while selected VBlank handlers
resume the queue, and gameplay objects poll the whole queue for readiness.
OpenGGF currently materializes most PLC art synchronously, so those readiness
intervals disappear.

This design first tests whether S1/S2 PLC completion is reproducible from ROM
submissions plus structural interrupt, phase, and lag state. A fixed-budget
logical queue is the preferred implementation, but it is not assumed correct
until it predicts captured ROM readiness edges. Art may remain eagerly
decompressed and registered for rendering. Recorded PLC state is diagnostic
evidence during this proof and does not drive the engine.

The work deliberately excludes:

- the completed S3K Kosinski module queue;
- the in-flight S3K direct Kosinski decompression queue;
- S3K's Nemesis queue, which needs its own consumer inventory and design;
- player, ending, or special-stage animation DPLCs that only select rendered
  tiles and have no queue-busy gameplay consumer;
- incremental host-side Nemesis decoding and VDP transfer emulation; and
- a new `PLC_QUEUE` hardware-timing authority kind.

## Source-of-truth findings

The existing hardware-timing contract gives S1/S2 PLCs a provisional
`NATIVE_SERVICE_QUEUE_PENDING_REVIEW` disposition. It does not prove that a
frame-level logical model is sufficient. The disassembly establishes:

- S1 `RunPLC` prepares the head entry and `ProcessPLC` resumes it. Selected
  VBlank handlers process either three or nine patterns
  (`docs/s1disasm/sonic.asm:775-784,860-870,1376-1515`).
- S2 `RunPLC_RAM` prepares the head entry and `ProcessDPLC` or
  `ProcessDPLC2` resumes six or three patterns
  (`docs/s2disasm/s2.asm:2148-2289`).
- Both games admit ordinary object or mode loops while the queue is pending.
  Queue consumers are catalogued in
  `docs/architecture/audits/2026-07-27-s1-hardware-timing-inventory.md` and
  `docs/architecture/audits/2026-07-27-s2-hardware-timing-inventory.md`.

The name `ProcessDPLC` in S2 is historical. This design concerns the general
PLC buffer, not playable-character dynamic pattern selection.

Several facts remain unproven:

- replay must identify every interrupt handler that actually services PLCs;
- lag VBlanks do not necessarily service the queue;
- water-split paths can defer the three-pattern work from VBlank into HBlank;
- a completed entry requires a later main-loop `RunPLC` preparation before the
  next entry can be serviced;
- completion visibility must be pinned relative to each polling consumer; and
- retail S1 and S2 publish the pattern count before Huffman preparation is
  complete, creating a documented interrupt race
  (`docs/s1disasm/sonic.asm:1392-1413`;
  `docs/s2disasm/s2.asm:2164-2191`).

The fixed tile budget proves progress per completed service invocation. It
does not by itself prove which invocations occurred or that an atomic
preparation model matches retail execution.

## Decision

### Evidence gate

Before committing to the logical implementation, diagnostic ROM captures must
observe:

- every PLC submission, clear, and replacement;
- `RunPLC`/`RunPLC_RAM` preparation begin and end;
- queue head, destination, patterns remaining, and queue-empty state;
- selected VBlank handler and whether service ran at 9/6/3 patterns;
- HBlank-deferred service;
- emulator lag and gameplay-loop admission; and
- the first consumer observation of the empty edge.

Clear and replacement captures include the buffer, `PatternsLeft`, and decoder
progress before and after the call. Retail `RunPLC` stores its advanced source
pointer back into the first buffer longword, while `ClearPLC` zeroes that
longword without clearing the other decoder scalars
(`docs/s1disasm/sonic.asm:1360-1368,1402-1408`;
`docs/s2disasm/s2.asm:2131-2139,2181-2187`). The evidence gate must prove every
covered production clear/replace occurs while no decoder is active. If not,
the native model is rejected until an amended design models the exact aliased
RAM and retail fault behavior; it must not invent a coherent preserved job.

One identical-input replay pair is sufficient to smoke-test recorder
repeatability. The native-model gate instead uses materially distinct
execution instances: individual-level movies, different level completions,
special-stage detours, and complete-run windows that reach equivalent
consumers with different preceding queue and interrupt histories. Instances
within one multi-level BK2 count separately when their submission/service
histories differ. A standalone predictor consumes only ROM PLC definitions
plus recorded structural phase/lag and consumer poll identity/order—not
recorded PLC progress or poll result—and must predict every preparation, pop,
and consumer-visible empty edge across that corpus.

The retail preparation race receives an explicit disposition:

1. prove it is not entered on the covered production routes and preserve it as
   a documented non-goal;
2. model the cycle-sensitive window; or
3. if it creates healthy, repeatable timing variation that structural replay
   cannot predict, stop and amend this design before introducing any recorded
   completion authority.

Diagnostic PLC fields never become replay inputs. Failure of the evidence gate
does not authorize a queue-empty hydration event.

If a required production lifecycle has no movie/save-state route, the report
marks it unavailable rather than manufacturing a duplicate route. Approval
requires all available materially distinct instances to match plus at least
one varied-history comparison for each common consumer family. A unique
consumer such as Final Zone may be single-instance covered when the corpus
contains only one authentic execution. A missing common consumer family with
no varied-history evidence leaves the result `EVIDENCE_INCOMPLETE`.
A compact derived evidence vector may be committed with the research report so
the predictor remains independently rerunnable; it contains structural rows
and observed diagnostic edges, not ROM bytes, gameplay state, or a runtime
fixture.

### Preferred approach after the gate: logical queue state with eager payloads

Each game receives a session-owned PLC timing queue. A request parses the PLC
definition from the user-supplied ROM and records one logical entry per ROM PLC
entry in FIFO order. The existing rendering path may immediately decompress
and register the associated art. Queue service consumes ROM-derived pattern
counts at the exact service point used by that game's active VBlank handler.

The logical queue is the authoritative owner of:

- entry order;
- head preparation state;
- remaining patterns in the active entry;
- destination metadata needed to identify and restore entries;
- queue-busy state;
- clear, replace, and append semantics; and
- rewind capture and restoration.

It is not the authoritative owner of rendered pixels. A queue becoming ready
allows the ROM-modeled consumer to advance; it does not trigger gameplay
mutations itself.

### Deferred fallback: trace-recorded completion edges

Adding a `PLC_QUEUE` timing stream would not remove the need for production
submission, identity, ordering, consumer, and rewind state. It would also make
trace replay more accurate than ordinary play unless production has an
independent scheduler. The approved hardware-timing registry therefore remains
unchanged during the evidence phase.

If the native predictor fails with identical submissions and structural
phase/lag, implementation stops. A separate design review must demonstrate an
independently identifiable, already-submitted, prepared queue job and the
smallest consumer-visible completion boundary before `PLC_QUEUE` can be
considered. Queue-state payloads, per-entry art bytes, and consumer mutations
remain forbidden.

### Rejected: queue-specific trace anchors

Starting a fixture after the queue drains is acceptable only when the fixture
explicitly excludes that lifecycle. Padding input, selecting a route-specific
anchor, suppressing rows, or hydrating queue state from trace data would mask
the missing production behavior and violate the comparison-only replay
contract.

### Rejected: one timing counter per consumer

The current Final Zone counter correctly preserves one known S1 symptom, but
duplicating that pattern for ARZ, results, Game Over, and special-stage
consumers would lose FIFO contention and clear/append semantics. The durable
owner is the game-wide PLC buffer.

## Architecture

### Shared queue kernel

This section is conditional on the evidence gate passing.

`com.openggf.level.resources.NemesisPlcServiceQueue` provides the mechanics
shared by S1 and S2. It has no game, zone, trace, or object knowledge.

Conceptual API:

```java
public final class NemesisPlcServiceQueue {
    public void replaceQueued(PlcDefinition definition,
                              List<Integer> patternCounts);
    public void append(PlcDefinition definition,
                       List<Integer> patternCounts);
    public void clearQueued();
    public void prepareHead();
    public void servicePatterns(int patternBudget);
    public boolean isBusy();
    public Snapshot capture();
    public void restore(Snapshot snapshot);
}
```

`replaceQueued` and `clearQueued` require the Task 1-proven idle-decoder
precondition. Non-idle calls fail rather than silently discard work or invent
a separately preserved job. `append` models `AddPLC`/`LoadPLC`. The queue
rejects a definition/count cardinality mismatch and negative pattern counts.

`prepareHead` models `RunPLC`/`RunPLC_RAM`: when no decoder is active and a
head entry exists, it initializes that entry but consumes no patterns.
`servicePatterns` models `ProcessPLC`/`ProcessDPLC`: it consumes up to the
specified number of patterns from the prepared head. On completion it removes
that entry, leaving the next entry unprepared until a later `prepareHead`.
This preserves the ROM's setup interval between entries.

The kernel stores a logical active decoder entry and queued descriptors only
after Task 1 proves production clear/replace never enters the retail aliasing
hazard. Both retain ROM source and VRAM destination plus remaining/total
pattern counts. It does not store decompressed bytes.

### ROM-derived pattern counts

Pattern counts are computed when a PLC is submitted using the same
user-supplied ROM entry that feeds rendering. `PlcParser.decompressEntryRaw`
provides the exact decompressed byte length; the logical count is
`bytes.length / Pattern.PATTERN_SIZE_IN_ROM`. A non-multiple is a hard
ROM/data error.

The result may be cached per ROM identity and source address, but the cache is
derived data, not queue state. No count is hard-coded in a zone or consumer.
The existing S1 Final Zone constants are removed once equivalence tests prove
the general queue produces the same timing.

### Game-owned façades

The shared kernel deliberately does not decide service cadence or call order.

### Audited producer boundary (2026-07-29 amendment)

Queue submission is owned by a concrete, already-implemented game transition,
not by a renderer cache, level decoder, or a speculative lifecycle phase. The
complete current routing scope and its ROM-to-Java evidence are maintained in
[`2026-07-29-s1-s2-plc-producer-call-site-audit.md`](../audits/2026-07-29-s1-s2-plc-producer-call-site-audit.md).
Task 5 routes every row marked `Route` there. The table is exhaustive for ROM
producers whose Java transition is represented, including game-owned title-screen,
credits-text next-demo prequeue, level-init, and title-card transitions; decoders and
renderers supply data and eager art,
but do not themselves create logical work. This boundary preserves eager
rendering while preventing it from becoming an unearned logical queue event. A
later implementation of an excluded ROM transition must first amend the audit
and producer-coverage test table.

`Sonic1PlcService` owns:

- parsing S1 PLC IDs from `Sonic1Constants.ART_LOAD_CUES_ADDR`;
- mapping `NewPLC`, `AddPLC`, and `ClearPLC` call sites to kernel operations;
- calling `prepareHead` at S1 `RunPLC` sites;
- choosing three- or nine-pattern service from the admitted S1 VBlank
  lifecycle; and
- exposing only `isBusy()` to S1 consumers.

`Sonic2PlcService` owns the corresponding S2 table and:

- `LoadPLC` append, `LoadPLC2`/replacement, and clear semantics verified
  against the disassembly;
- `RunPLC_RAM` head preparation;
- six-pattern `ProcessDPLC` and three-pattern `ProcessDPLC2` service; and
- `isBusy()` for S2 consumers.

The exact association between VBlank routine and budget remains in the
game-owned façade or lifecycle provider. Shared runtime code does not branch
on game identity.

### Semantic lifecycle adapter

The ordinary-level-only `PlcVBlankService` seam is not sufficient. In the
retail programs the selected VBlank routine is a property of the current ROM
loop, and `RunPLC` / `RunPLC_RAM` is a later, separate call made by only some
of those loops. Treating every non-gameplay row as either "ordinary service"
or "VBlank only" loses title, fade, results, credits, special-stage, and pause
semantics.

Task 4 replaces that partial seam with one optional session-owned port:

```java
public enum PlcLifecyclePhase {
    LAG,
    TITLE_SCREEN,
    LEVEL_SELECT,
    LEVEL_TITLE_CARD,
    ORDINARY_LEVEL,
    PALETTE_FADE,
    SPECIAL_STAGE,
    SPECIAL_STAGE_RESULTS,
    TWO_PLAYER_RESULTS,
    CREDITS_TEXT,
    CREDITS_DEMO,
    CREDITS_DEMO_FADE,
    ENDING,
    POST_CREDITS,
    NORMAL_PAUSE,
    SPECIAL_STAGE_PAUSE
}

public interface PlcLifecycleService {
    void serviceVBlank(PlcLifecyclePhase phase);
    boolean hasPreparationBoundary(PlcLifecyclePhase phase);
    void prepareAfterLoop(PlcLifecyclePhase phase);
}
```

The enum describes the ROM loop, not a game, zone, route, trace, or rendering
state. `GameLoop`, `LevelFrameStep`, and the equivalent headless owner pass a
semantic phase; `Sonic1PlcService` and `Sonic2PlcService` privately map that
phase to their own VBlank handler and decide whether the loop has a native
preparation call. S3K registers no `PlcLifecycleService`.

There must be one PLC lifecycle owner per represented VBlank. A palette fade
that owns the ROM iteration selects `PALETTE_FADE` instead of also selecting
the underlying level/title/special phase. A cosmetic OpenGGF overlay does not
become a PLC fade phase merely because `FadeManager.isActive()` is true. This
matters because the current fade presentation advances outside `GameLoop` and
can overlap an underlying mode update; deriving PLC cadence from
`FadeManager.isActive()` would double-service the queue. Transition call sites
must explicitly identify the native palette-fade loop they represent, and
tests must prove one service and at most one preparation per emulated VBlank.

`PlcFrameLifecycleCoordinator` is the concrete single-owner mechanism. It is
session-owned beside the game PLC service and creates one non-reusable
`PlcLifecycleFrame` token for every represented VBlank:

```java
public final class PlcFrameLifecycleCoordinator {
    public PlcLifecycleFrame latchBeforeFadeUpdate();
    public NativeBlockingFade beginNativeBlockingFade();
}

public interface PlcLifecycleFrame {
    boolean claim(PlcLifecyclePhase phase);
    void prepareAfterLoop(PlcLifecyclePhase phase);
    boolean isOwnedBy(PlcLifecyclePhase phase);
    void finish();
}

public interface NativeBlockingFade extends AutoCloseable {
    Runnable wrapCompletion(Runnable completion);
}
```

`beginNativeBlockingFade()` is called by a transition owner immediately before
it starts a `FadeManager` operation that represents the retail blocking
palette-fade subroutine. The returned marker stays active across all fade
iterations. Its wrapped completion first ends the marker and then invokes the
mode-changing callback. A fade with no behavioral callback still uses the
wrapper around a no-op so the marker ends at the same logical boundary.
Cosmetic overlays use the existing `FadeManager` methods without a marker.
The marker is session state and is reset with the session; Task 7 captures it
with the lifecycle service if a rewindable native fade owner is introduced.

#### Audited S1/S2 fade owners

Every production `startFadeTo*` / `startFadeFrom*` call reachable during an
S1/S2 session is classified by the ROM loop it represents. “Marked” means the
owner acquires `NativeBlockingFade` immediately before the call and wraps its
completion, including a no-op completion. “Unmarked” means the fade is host
presentation or concurrent palette work and the named underlying phase owns
PLC cadence.

| owner | calls | classification |
|---|---|---|
| `GameLoop` special-stage entry, results entry/exit/reveal, and debug equivalent | transition and callback-started reveal fades | marked native palette loops; each reveal acquires a new marker |
| `SpecialStageEntryPresentationController.reveal` | fade from black/white after setup | marked; `GameLoop` passes session lifecycle access |
| `GameLoop` title-to-level, level-select enter/exit, respawn, next-act, next-zone, explicit zone/act, ending enter/reveal/exit | transition and reveal fades | marked native palette loops |
| `GameLoop` master-title, escape/application exit, and donated data-select routes | all local fades | unmarked host/presentation routes |
| `GameLoop` bonus-stage routes | all local fades | S3K-only; outside this adapter |
| `Engine` bootstrap/master-title, `LegalDisclaimerScreen`, `TraceSessionLauncher.startFadeOut` | all local fades | unmarked host/tool presentation |
| `Sonic1ResultsScreenObjectInstance` | giant-ring white transition; next-level black transition and reveal | marked; callback changes remain on the outgoing token |
| S2 `ResultsScreenObjectInstance` | next-level black transition and reveal | marked |
| `Sonic1CreditsManager` | initial text reveal; text-to-demo/final fade; demo reveal; return-to-text reveal | marked |
| `Sonic1CreditsManager.updateDemoPlaying` | 60-frame `Level_FadeDemo` dimming | unmarked; provider selects `CREDITS_DEMO_FADE`, service three with no preparation |
| `Sonic1EndingProvider` | post-credits black transition and reveal | marked |
| `Sonic2EndingProvider` | cutscene/slide black transitions and credit/logo reveals | marked |
| `Sonic1SpecialStageManager` | 60-frame `SS_FinLoop` brightening | unmarked; S1 special-stage/continue has no PLC service or preparation |
| `Sonic1EndingSonicObjectInstance.triggerFlash` | ending emerald white-out/return | unmarked; `ENDING` retains service nine and no preparation |

Route-sensitive methods classify the route, not just the Java method:
title-to-`LEVEL` is marked, while title-to-donated-`DATA_SELECT` is unmarked.
A callback-started inverse reveal ends the outgoing marker and acquires a new
one. The completion-frame token remains owned by the outgoing
`PALETTE_FADE`; the reveal first services on the next token.

The three unmarked S1 gameplay fades are deliberate. `SS_FinLoop` uses the
continue VBlank handler while continuing objects/layout, `Level_FadeDemo`
uses the level VBlank handler but omits the ordinary demo loop's `RunPLC`, and
`End_AllEmlds` uses the ending handler while continuing objects/deformation.
Their semantic owners are respectively `SPECIAL_STAGE`,
`CREDITS_DEMO_FADE`, and `ENDING`. Marking any as `PALETTE_FADE` would replace
its reviewed cadence; treating `Level_FadeDemo` as ordinary `CREDITS_DEMO`
would invent preparation on all 60 iterations.

`latchBeforeFadeUpdate()` runs before `FadeManager.update()`:

- with an active native-fade marker it permanently claims the token as
  `PALETTE_FADE` and calls `serviceVBlank(PALETTE_FADE)`;
- without the marker it returns an unclaimed token. The actual title, level,
  special, results, credits, or pause owner claims it after the cosmetic fade
  update, once admission has resolved the phase; and
- a second claim never services again. If the token is already fade-owned, an
  underlying or newly entered mode can still run its non-PLC logic but its PLC
  claim is suppressed.

A native blocking fade iteration has this exact order:

```text
latch PALETTE_FADE and service it
-> FadeManager.update() / palette-loop work
-> prepareAfterLoop(PALETTE_FADE)
-> run or skip underlying mode logic with the same already-owned token
-> finish token
```

If `FadeManager.update()` completes the fade and changes mode, the token
remains owned by the outgoing `PALETTE_FADE`; ending the marker does not mutate
the token. The incoming mode first claims a fresh token on the next represented
VBlank. `finish()` rejects a second service, a duplicate preparation, a
required-but-missing preparation, or reuse on another logical iteration.

Logical fade advancement moves out of unconditional rendering cadence for an
active gameplay session. `GameLoop` drives the coordinator and the
session-owned `FadeManager.update()` once per logical iteration; `Engine`
retains the UI-pipeline fade update only when no gameplay lifecycle
coordinator owns the fade. This makes user-recording fast-forward and any
multi-step host update create one token and one fade update per represented
VBlank, not per rendered host frame. `RecordingFrameDriver`,
`TraceSessionLauncher` replay steppers, and live-rewind steppers use the same
coordinator with the session fade manager. Headless execution supplies the
same fade update callback without OpenGL.

The reviewed mappings are:

| semantic phase | S1 service | S1 prepare after loop | S2 service | S2 prepare after loop |
|---|---:|---|---:|---|
| `LAG` | none | no | none | no |
| `TITLE_SCREEN` | 9 | yes | 6 | yes |
| `LEVEL_SELECT` | 9 | yes | 6 | no for the implemented menu loop |
| `LEVEL_TITLE_CARD` | 9 | yes | 6 | yes |
| `ORDINARY_LEVEL` | 3 | yes | 3 | yes |
| `PALETTE_FADE` | 9 | yes | 6 | yes |
| `SPECIAL_STAGE` | none | no | 3 | yes |
| `SPECIAL_STAGE_RESULTS` | 9 | yes | 3 | yes |
| `TWO_PLAYER_RESULTS` | not applicable | no | 6 | yes |
| `CREDITS_TEXT` | 9 | yes | none | no |
| `CREDITS_DEMO` | 3 | yes | not applicable | no |
| `CREDITS_DEMO_FADE` | 3 | no | not applicable | no |
| `ENDING` | 9 | no | none | no |
| `POST_CREDITS` | 9 | no | none | no |
| `NORMAL_PAUSE` | 3 | no | 3 | no |
| `SPECIAL_STAGE_PAUSE` | none | no | none | no |

These rows come from the reviewed handler and call-site inventories:
S1 handlers `$04/$0C/$12/$18` service nine, `$08/$10` service three, and
`$0A/$16` service none; S2 handlers `$04/$0C/$12/$16` service six,
`$08/$0A/$10` service three subject to the special-stage pause branch, and
`$18` services none. The matching loop bodies place `RunPLC` /
`RunPLC_RAM` after their object/menu/palette work. In particular,
`Level_MainLoop`, both level-title-card loops, both palette-fade loops, both
title loops, S1 credits pages, S2 special stage/results, and S2 two-player
results contain preparation calls. The exceptions in the table were checked
as absent calls, not inferred from a zero workload.

Normal S1 `CREDITS_DEMO` represents `Level_MainLoop`, whose level handler
services three and whose loop calls `RunPLC`. When
`Sonic1CreditsManager.State.DEMO_FADING_OUT` represents `Level_FDLoop`, the
provider overrides the semantic phase to `CREDITS_DEMO_FADE`: the same level
handler services three, but the loop contains no `RunPLC`.

The apparent S1 `ENDING` exception is intentional: VBlank handler `$18`
aliases the nine-pattern title-card service, but `End_MainLoop` has no
`RunPLC`. S2's ending handler does no PLC service. Likewise pause loops service
an already prepared entry but never prepare a successor. Ordinary one-player
level results and Game Over remain `ORDINARY_LEVEL`; their objects are
consumers/producers inside the normal object scan, not separate frame modes.
S2 two-player results use their distinct menu handler only when that engine
lifecycle exists.

`prepareAfterLoop` is a semantic name for the reviewed call site, not a
generic end-of-frame cleanup. It runs after that loop's producer/consumer scan
at the position corresponding to `RunPLC` / `RunPLC_RAM`. A service that
completes the active entry therefore cannot spend the same VBlank's remaining
budget on its successor: the successor becomes prepared only after the loop,
for a later VBlank. `LAG`, pause, S1 special stage, S1 ending, S1 post-credits,
S2 level-select, and S2 ending never receive an invented preparation.

Task 4 does not call `append`, `replaceQueued`, `clearQueued`, or any renderer.
Those producer/render transactions remain wholly in Task 5. Its lifecycle
tests may use a recording or pre-seeded queue service, but must not add
production submissions to make a phase test convenient.

#### Task 4 assumptions and test seams

- S1/S2 water HBlank deferral changes where the three-pattern service executes
  inside the interrupt, not what the admitted main loop can observe. No
  production consumer runs between VBlank entry and deferred `Do_Updates`.
  The lifecycle port therefore publishes the service before the main-loop scan
  for both direct and deferred cases. The Task 1 predictor remains the
  cycle-order oracle; a test must retain the same consumer-visible edge.
- OpenGGF currently advances `FadeManager` before `GameLoop` and can update an
  underlying mode while a fade overlay is active. Task 4 changes ownership,
  not rendering: `PlcFrameLifecycleCoordinator` latches before the logical
  fade update, while actual drawing remains in the UI pipeline. Live and
  headless tests inject the fade update callback and require identical token
  events.
- The implemented engine has one-player special-stage results and S1 credits
  owners. It does not currently expose every retail S2 two-player result/menu
  lifecycle, and special-stage native pause is not represented by the level
  pause admission path. Missing owners stay dormant; adding a fake mode solely
  to exercise a table row is outside Task 4.

### S2 player-life authority boundary

The current session exposes a semantic `PlayerCharacter`, which is sufficient
to distinguish the represented Tails-alone one-player route from the default
one-player route. It does not expose the retail two-player enable flag or the
player-graphics sign bit consumed by `PlrList_Std1`. Task 5 therefore preserves
the currently representable one-player branch: no life PLC unless the semantic
character is Tails-alone, in which case it submits PLC `9`. It must not infer
the missing 2P values from a renderer, trace, or config string. A future
session-owned `Sonic2PlayerArtModeAuthority` supplies `{twoPlayer, playerMode,
alternateGraphics}` at level setup and owns the complete `6`/`7`/`8`/`9`
selection; until then, those unrepresented combinations remain outside the
producer contract.

The seam is injected into `Sonic2LevelInitProfile` by the game module/session
bootstrap. Its default authority is
`{twoPlayer=false, playerMode=PlayerCharacter, alternateGraphics=false}` and
returns `OptionalInt.empty()` for Sonic and Sonic-and-Tails and `OptionalInt.of(9)`
for Tails-alone. The retail combinations are deliberately not constructable
from that default authority.

### Transactional eager/logical publication

S2 runtime requests use a two-phase game-owned boundary. Phase one parses the
logical PLC and builds every not-yet-registered eager sprite sheet without
changing provider registration or the queue. Phase two verifies queue capacity,
commits the queue operation, and publishes the already-built sheets through a
non-throwing registration operation. A preflight error exposes neither side;
cache hits still submit the logical operation. S1's eager art owner has no
per-request mutable renderer operation, so its corresponding prepared renderer
payload is an explicit no-op and its queue submission remains game-owned.

An animal/explosion pair is one transaction, not two adjacent transactions.
The publisher preflights the full ordered batch against one queue snapshot and
one renderer payload, commits the entire prepared queue batch, registers the
prepared sheets, and then performs exactly one cache refresh. A failed
preflight causes no queue change, no renderer registration, and no refresh.
- The BK2 evidence corpus did not reach Game Over. That does not create another
  lifecycle phase: both games' Game Over consumers execute under the ordinary
  level handler and use the already-covered append/service/poll contract.
- A phase transition or fade completion can occur during one host update. The
  latched token makes the outgoing native fade authoritative for that
  represented VBlank. Tests change mode from the completion callback and prove
  the incoming phase receives neither service nor preparation until the next
  token. A two-step fast-forward test proves the rule is per logical iteration,
  not per rendered host frame.

### Session ownership and frame ordering

Each service belongs to the active game session and is reachable through that
game's module/service graph. Objects receive it through existing injected
services; they never call `getInstance()`.

The coordinator is created and stored by `GameplayModeContext`. A small
game-neutral `NativeFadeLifecycle` port exposes only
`beginNativeBlockingFade()`; `PlcFrameLifecycleCoordinator` implements it.
`ObjectServices.nativeFadeLifecycle()` delegates through
`DefaultObjectServices` to its existing session context.

Provider fades use explicit capability injection. S1/S2 ending providers
implement `NativeFadeLifecycleAware`; `GameLoop` binds the active context's
port before `initialize()`. `Sonic1EndingProvider` passes it into each new
`Sonic1CreditsManager`; `Sonic2EndingProvider` retains it directly.
`SpecialStageEntryPresentationController` receives it as a method argument
from `GameLoop`. There is no coordinator accessor on `GameServices`, provider
lookup through `SessionManager`, `getInstance()`, or game-name branch.

The ending provider also exposes a neutral optional semantic phase override.
`Sonic1EndingProvider.plcLifecyclePhaseOverride()` returns
`CREDITS_DEMO_FADE` only while its manager reports `DEMO_FADING_OUT`;
otherwise it is empty. The shared credits lifecycle owner uses the override
before its ordinary `EndingPhase` mapping. This exposes the concrete ROM loop
without inspecting the provider type, game id, or manager internals.

```java
public interface EndingProvider {
    default Optional<PlcLifecyclePhase> plcLifecyclePhaseOverride() {
        return Optional.empty();
    }
}
```

Queue processing must preserve ROM visibility:

1. the selected VBlank service consumes the current prepared head;
2. the admitted main loop begins;
3. `RunPLC`/`RunPLC_RAM` prepares a queued head when the containing ROM loop
   does so;
4. events and objects observe `isBusy()` at their disassembly-defined point;
5. an object/event PLC operation mutates logical state synchronously at that
   exact producer call site; and
6. later object slots in the same scan observe the mutation, while earlier
   slots are not retroactively blocked.

There is no unconditional shared-frame service call. Title-card, fade,
special-stage, results, credits, and ordinary level handlers use the semantic
lifecycle port above. Shared owners select the phase; game-owned services
select the budget and preparation behavior.

Lag-only and HBlank-deferred rows receive explicit semantic operations. A
generic `VINT_SERVICE` callback is insufficient unless the evidence gate proves
that it faithfully represents the selected handler and deferred work.

For the ordinary level loop, `LevelFrameStep` owns
`serviceVBlank(ORDINARY_LEVEL)` before events/objects and
`prepareAfterLoop(ORDINARY_LEVEL)` after the complete producer/consumer scan.
Its lag and pause entry points take an explicit semantic phase so a lag row
does not accidentally suppress the native three-pattern pause handler. The
headless and live paths delegate to the same owner.

The phase-aware frame API is canonical:

```java
LevelFrameResult execute(
        LevelFrameContext context,
        PlcLifecycleFrame frame,
        PlcLifecyclePhase phase,
        LevelManager level,
        Camera camera,
        Runnable spriteUpdate,
        StepWrapper wrapper);

LevelFrameResult executeWithPause(
        LevelFrameContext context,
        PlcLifecycleFrame frame,
        PlcLifecyclePhase activePhase,
        PlcLifecyclePhase pausePhase,
        LevelManager level,
        Camera camera,
        Runnable spriteUpdate,
        boolean startEdgePressed,
        StepWrapper wrapper);

void executeHardwareTimedObjectScan(
        LevelFrameContext context,
        PlcLifecycleFrame frame,
        PlcLifecyclePhase phase,
        Runnable objectScan);

void serviceVBlankOnly(
        LevelFrameContext context,
        PlcLifecycleFrame frame,
        PlcLifecyclePhase phase);
```

The old no-phase production overloads are removed. Tests that need an ordinary
level convenience create a token and pass `ORDINARY_LEVEL` explicitly.
`execute` claims the supplied phase before any phase-owned work. It invokes
`prepareAfterLoop(phase)` immediately after the object/event producer and
consumer scan and after the existing `POST_OBJECTS` hardware boundary, before
the early return for a transition requested during that scan. Setup-only
returns occur before claiming a token. An exception does not invent a
preparation through `finally`.

`executeWithPause` applies the Start edge once. If the game remains paused, it
calls `serviceVBlankOnly(..., pausePhase)` and returns without preparation. If
the press unpauses, it delegates to `execute(..., activePhase)` in the same
iteration. Normal level callers pass
`(ORDINARY_LEVEL, NORMAL_PAUSE)`; a represented special-stage pause passes
`(SPECIAL_STAGE, SPECIAL_STAGE_PAUSE)`.

`serviceVBlankOnly` is restricted to `LAG`, `NORMAL_PAUSE`, and
`SPECIAL_STAGE_PAUSE`; it never prepares. Recorded level lag, held trace
admission, and recorded special-stage lag pass `LAG`. S3K-only setup/bonus
rows that represent no S1/S2 PLC handler use a separate hardware-boundary-only
helper and do not claim the PLC token.

`executeHardwareTimedObjectScan` uses the same token and phase while preserving
the existing S3K boundaries around its scan. It prepares only when the game's
phase matrix requires it. An S3K module has no PLC adapter, so its title-card
and special-stage calls alter no PLC state.

The S2 locked title-card delegation passes one unclaimed token and
`LEVEL_TITLE_CARD` directly to the phase-aware `execute`; it is not wrapped by
an external PLC call and cannot fall through an ordinary convenience overload.
The S1 minimal title-card scan and S3K provider scan pass the same semantic
phase to `executeHardwareTimedObjectScan`. Thus title-card provider work,
object work, service, and preparation share one token.

Every production caller is migrated:

- `GameLoop`: ordinary level, locked title card, title/level-select,
  special-stage/results, credits/ending, native pause, trace lag, bonus and
  transition-held rows;
- `RecordingFrameDriver`: admitted ordinary frames, recorded lag/held rows,
  special-stage scans and results;
- `TraceSessionLauncher.VisualTraceRewindStepper`: ordinary replay and native
  pause admission;
- `LiveRewindStepper`: ordinary level replay and native pause admission;
- `SpecialStageStepper`: special-stage replay, including the explicit
  special-stage pause phase when that owner exists; and
- all direct `executeWithPause` callers, which supply both active and pause
  phases instead of relying on the removed implicit ordinary path.

Paired driver tests require ordinary, lag, pause, S2 locked title card,
special stage, and special-stage results to emit the same token/service/prepare
sequence in live and headless execution.

The PLC lifecycle port is deliberately separate from
`HardwareServiceBoundary`. The existing S3K order remains:

```text
VINT_SERVICE -> PRE_MAIN_LOOP -> object/event scan -> POST_OBJECTS
```

Adding an S1/S2 semantic call must not reorder, duplicate, or conditionally
skip any of those boundaries, and must not call
`RuntimeArtCoordinator.afterTimingService` itself. S3K's direct Kos queue
continues retiring only at `PRE_MAIN_LOOP`; its module coordinator continues
running only at `POST_OBJECTS`.

### Rendering integration

Logical submission and art materialization are separate effects of one
game-owned request:

```text
ROM PLC request
  ├─ parse entries and derive pattern counts
  ├─ append/replace logical queue
  └─ eagerly materialize/register render art
```

Rendering remains immediately safe in OpenGGF. Gameplay consumers must use
logical `isBusy()`, never renderer availability, sheet registration, atlas
state, or host decompression completion.

Repeated requests still affect logical FIFO state even when the renderer
already has the sheet. Existing "already registered" render deduplication must
not suppress a ROM queue submission.

### Rewind

The snapshot contains:

- active decoder state plus queued descriptors in order;
- source address and destination tile for each entry;
- total and remaining pattern counts;
- whether the head is prepared; and
- any façade lifecycle state needed to reproduce the next service call.

Restore reconstructs logical state without parsing the trace, resubmitting
work, changing queue order, or registering art a second time. Each game façade
is the sole rewind registration owner for its kernel and lifecycle state; the
kernel is not independently registered a second time. Guard tests cover new
final scalars, collections, and static/session ownership.

## Consumer migration

### Sonic 1

The first delivery migrates:

- level title-card readiness;
- Final Zone boss startup;
- level-results card;
- Game Over card;
- special-stage results/emerald object; and
- the surrounding special-stage results loop where represented by the engine.

Credits and level-select callers are connected when their engine lifecycle
owner exists. Unsupported/dormant objects are documented rather than given a
fake owner.

The Final Zone migration replaces `Sonic1FzPlcTimingQueue`. The boss continues
to increment RNG on every busy frame; it simply polls the shared S1 service.

### Sonic 2

The first delivery migrates:

- level title-card readiness;
- ARZ boss initialization;
- level-results card;
- Game/Time Over object;
- special-stage results object and results loop; and
- two-player results where the lifecycle exists.

All runtime PLC producers in implemented level events and bosses must submit
to the service even if their art was eagerly loaded earlier. The initial
inventory includes WFZ, OOZ, MTZ, ARZ, DEZ, signpost/results, animals,
capsule, explosions, and boss PLC requests. Missing producer behavior is
implemented from the disassembly rather than inferred from a poll symptom.

ARZ is a consumer, not the owner: it polls the game-wide queue and contains no
ARZ-specific timing constant.

## Error handling

- Invalid PLC IDs fail at the game façade boundary with the game and PLC ID.
- A parsed definition/count mismatch is an invariant failure.
- A decompressed length that is not a whole number of patterns fails before
  queue mutation.
- Queue overflow follows verified ROM capacity behavior. Until that behavior
  is pinned for both games, implementation must fail closed rather than drop,
  reorder, or overwrite an entry silently.
- Restoring an invalid snapshot fails with the offending entry and field.
- Rendering failure and logical queue mutation are transactional:
  validate/decompress/materialize all ROM-derived payloads without publishing
  either effect, then commit the logical mutation and a non-throwing prepared
  renderer registration. If registration can still fail, roll back before
  exposing the request. A partial logical submission is forbidden.
- Deferred fail-closed producer publication is bookkeeping only. A game-owned
  event owner retries pending work before its Dynamic Level Event routine, but
  retry failure or success never consumes or suppresses that ROM DLE frame.
  The original producer boundary already advanced the event routine before
  submitting the PLC, so a retry must not rerun that one-shot transition.
  Failed retries remain pending while the current DLE routine executes exactly
  once; successful retries clear the pending request and then execute that
  routine exactly once. This matches S1 `DLE_FZ_Main`, where `AddPLC` falls
  through to `DLE_SBZ2_SetBoundary`, and applies to the equivalent S2
  game-owned event boundary.

## Testing strategy

### Queue kernel

Unit tests prove:

- append, queued-descriptor replacement, and queued-descriptor clear;
- rejection of clear/replace while a decoder is active under the
  evidence-gated idle precondition;
- preparation consumes no patterns;
- three-, six-, and nine-pattern budgets;
- entry completion and the setup interval before the next entry;
- whole-buffer busy semantics;
- repeated identical submissions remain distinct FIFO work;
- invalid pattern counts do not mutate the queue; and
- snapshot round trips at unprepared, partial, entry-boundary, and empty
  states.

### Diagnostic prediction

Before production queue code, recorder tests and capture analysis prove:

- one identical-input smoke pair proves recorder stability;
- materially distinct executions exercise different submission histories,
  service exposure, and consumer latencies;
- structural phase/lag plus ROM-derived pattern counts predicts every
  execution independently;
- HBlank deferral preserves the observed same-frame or next-frame visibility;
- lag rows either service or skip work exactly as the selected ROM handler
  does; and
- no covered route enters the partial-preparation interrupt window, unless a
  reviewed retail-race model is included.

The predictor must fail when one service call, handler selection, preparation
bubble, or lag classification is deliberately shifted.

### ROM-backed parity

With discovered S1/S2 ROM paths:

- parse representative standard, zone, boss, results, and Game Over PLCs;
- derive all pattern counts from ROM bytes;
- compare calculated drain frames with an independent test oracle based on
  the disassembly service algorithm; and
- prove S1 Final Zone timing matches or corrects the existing narrow model
  before removing it.

### Lifecycle and consumers

Focused integration tests prove exact observation order:

- completion in VBlank is visible to the following object scan;
- a head prepared in the main loop is not serviced retroactively;
- S1 Final Zone RNG advances once per busy boss frame;
- S2 ARZ initialization remains blocked by unrelated earlier FIFO entries;
- an earlier-slot producer makes the queue busy for a later-slot consumer in
  the same scan, while the inverse order does not change the earlier result;
- clear/replace changes queued descriptors immediately while idle and rejects
  a non-idle invocation;
- renderer deduplication does not suppress logical resubmission;
- clear/replace operations change consumers on the correct frame; and
- rewind across completion reproduces the same readiness edge.

### Trace policy and regression

Source guards continue to forbid PLC authority from physics or aux trace data.
No `PLC_QUEUE` hardware timing fixture is added. Existing short traces may
remain scoped before unimplemented lifecycle paths, but complete-run tests
must not suppress queue-active rows.

Focused S1 and S2 trace replays covering Final Zone and ARZ are run when the
required ROM fixtures exist. The full Maven suite then verifies no S3K Kos
module or in-flight Kos decompression behavior was changed.

### Deliberately skipped level presentation

Trace replay exposed a production lifecycle gap rather than a need for trace
timing authority. A normal ROM level entry services its initial PLC work while
the title card and palette transition are running. A headless or live caller
that deliberately omits that presentation must still arrive at the same
post-presentation PLC boundary. Simply consuming the title-card request leaves
the full level-start queue in place and delays later boss/results work by
hundreds of gameplay frames.

The skip is a production level-entry operation. Its only inputs are the active
game module, the current ROM-backed level header, the game-owned PLC service,
and the fact that the level transition owner has selected the
presentation-omitted path. It must not accept `TraceData`, `TraceFrame`,
metadata, BK2 offsets, route names, zone exceptions, fixture identity, or
recorded queue/readiness values. Headless level loads and live tooling that
consume a pending initial-title-card request call the same operation. Trace
code may select the existing presentation-omitted transition, but may neither
choose its phase counts nor manipulate an S1/S2 PLC service directly.

The native sequences are:

- **S1:** `Level_TtlCardLoop` repeatedly performs the title-card VBlank,
  object scan, and `RunPLC` until the initial primary + `Main2` queue is empty.
  `LevelDataLoad` then appends the second PLC byte at level-header offset
  `+4`. Four `id_VBlank_Levels` delay frames do not call `RunPLC` and therefore
  do not alter the queue. `PalFadeIn_Alt` then executes exactly 22 palette-fade
  iterations in VBlank-before-`RunPLC` order. The shared lifecycle coordinator
  therefore drains the initial queue through `LEVEL_TITLE_CARD`, publishes the
  ROM header's secondary PLC, and executes 22 `PALETTE_FADE` service/prepare
  iterations. It deliberately does **not** drain the secondary queue.
- **S2:** the first `Level_TtlCard` loop performs title-card VBlank,
  `RunObjects`, and `RunPLC_RAM` until both title-card placement and the
  initial primary + `Std2` queue are complete. `loadZoneBlockMaps`, called
  immediately after `LoadZoneTiles`, then appends the level-header PLC byte at
  offset `+4` before ordinary gameplay begins. The
  initial-presentation boundary therefore drains the first queue through
  `LEVEL_TITLE_CARD` and appends that ROM-selected secondary cue without
  draining it. Both a visible title-card release and a deliberately omitted
  locked presentation use this game-owned boundary. The later
  `Obj34_LoadStandardWaterAndAnimalArt` calls remain owned by the title-card
  text-exit overlay and are not folded into this earlier release.

The S1 order is independently observable. At the first compared FZ gameplay
frame of the complete-run BK2, BizHawk reports PLC 15 partially active at its
seventh descriptor with the final six descriptors queued. The PLC 15
ROM-derived pattern counts are
`16,41,31,15,15,20,49,4,48,12,8,16,14`. Twenty-two lifecycle iterations mean
the first iteration only prepares the head and the following 21 VBlanks
service nine patterns each, losing the unused per-frame budget at descriptor
boundaries exactly like `ProcessPLC_9Tiles`. That leaves descriptor seven with
22 patterns and the observed six-entry tail. This derives the state from ROM
work and native control flow; the capture is validation, never runtime input.

The skip operation is idempotent per level-entry transition: a requested
presentation is either displayed normally or consumed once by the skip owner.
It is not a general queue-drain helper callable from replay bootstrap. Source
and reflection guards keep all trace types out of the production API and
forbid replay/bootstrap code from calling S1/S2 queue mutation or service
methods.

On the visible path, the outer title-card frame must finish its already-claimed
`LEVEL_TITLE_CARD` preparation before invoking the post-title completion
owner. In S2 the last `RunPLC_RAM` precedes `LoadZoneTiles` and
`loadZoneBlockMaps`; preparing after the completion owner would incorrectly
promote the newly appended header-secondary descriptor on that old locked
frame. S1 likewise begins its post-title synthetic fade sequence only after
the final locked-title preparation has finished.

### Validation blockers exposed by queue-accurate results waits

Queue readiness can extend an end-of-act card far enough to expose lifecycle
bugs that were previously hidden beyond a trace's early terminal boundary.
Those bugs are corrected from their ROM owners; they are not compensated by
changing queue budgets or consuming trace timing:

- S2 `Obj3A` sets its post-tally timer to `$B4` when the accumulated bonus is
  below 1000 and `$12C` when it is at least 1000. The game-owned S2 results
  object must select 180 or 300 from its own accumulated bonus. The common
  results base must not gain an S2 rule.
- S1 `v_endcard` is one fixed results-card slot. If two simultaneously loaded
  signposts reach `GotThroughAct`, the second handoff observes/reuses the
  already active S1 results card rather than allocating another card or
  replacing its PLC again.
- If gameplay throws after claiming a PLC lifecycle frame, end-of-frame
  preparation validation must not replace that primary exception. The
  validation failure is attached as suppressed context so the actual owner
  remains diagnosable.
- S1 `Got_SBZ2_MoveOut` reaches `got_finalX` on one object scan and returns.
  Only the following scan, which begins with equal current and final
  positions, advances the ring-bonus card to `Got_SBZ2_Boundary`; the first
  `+2` right-boundary increment occurs on the scan after that. The consolidated
  Java results card must retain those three distinct scans instead of entering
  the boundary routine on the movement that first reaches `got_finalX`.

## Delivery boundaries

This design should be implemented after the S3K direct Kos decompression
worktree lands or rebased onto its integrated result. The implementation may
reuse generic session/rewind conventions introduced there, but must not
generalize or edit its S3K queue semantics as part of this scope.

The evidence phase is complete only when the available varied-history ROM
corpus and the structural predictor establish whether the native model is
sufficient. If it
passes, the implementation is complete when S1/S2 queue-busy gameplay behavior
derives from ROM-backed logical queues in ordinary play and trace replay, all
implemented producers/consumers use the game-owned services, the narrow S1
Final Zone counter is retired, and no trace-derived completion driver exists.
If it fails, the design and plan must be amended and re-reviewed before
production implementation continues.
