# S2 Wing Fortress Zone Parity Design

## Requirements

Goal: make Sonic 2 Wing Fortress Zone behave as close to ROM as the engine can prove, with work driven by disassembly citations and trace replay evidence.

Acceptance criteria:
- `TestS2WfzLevelSelectTraceReplay` advances from the current documented frontier or passes.
- Focused WFZ event, Tornado, boss, and object tests cover any changed ROM behavior.
- Existing known-green traces used as guards remain green.
- `docs/status/trace-frontier-log.md` records any WFZ frontier movement or unchanged result.

Non-goals:
- Do not reimplement already-working WFZ objects without a concrete parity gap.
- Do not add trace route, zone, frame, or fixture carve-outs.
- Do not hydrate engine state from trace CSV/aux data during replay.

Constraints:
- ROM `x_pos`/`y_pos` map to centre-coordinate APIs.
- WFZ object/runtime assets must load from the user ROM, not `docs/` assets.
- Shared behavior changes must be disassembly-backed and cross-game-safe, or narrowly owned by S2/WFZ object/event code.

## Exploration Synthesis

WFZ is partially implemented. Current source contains WFZ-specific objects, badniks, art loaders, palette switching, Tornado start/end logic, and the ObjC5 boss. `Sonic2ObjectProfile` includes most WFZ route IDs: Obj80, Obj8B, ObjAD/AE, ObjB2-B6, ObjB8-BA, ObjBC-BE, ObjC0-C2, ObjC5, ObjD9.

Concrete gaps closed during implementation:
- `Sonic2WFZEvents` now requests the ROM WFZ boss/Tornado PLCs at the secondary-event thresholds instead of logging stubs.
- `Sonic2LevelEventManager` now serializes WFZ BG offsets, BG speed, and secondary routine state for rewind.
- The WFZ level-select trace now passes end-to-end with `All frames match trace. No divergences.`

## Architecture Decision

Use a trace-first parity workflow. The primary implementation surface is existing S2 zone event/object code: `Sonic2WFZEvents`, `Sonic2LevelEventManager`, WFZ object classes, and ObjC5 boss classes. Extend existing services and runtime-owned frameworks only when the ROM behavior needs state that is not currently represented.

WFZ event state remains owned by `Sonic2WFZEvents`; `Sonic2LevelEventManager` may expose extra rewind serialization for that state, following the existing HTZ/CPZ/CNZ pattern. Object behavior remains object-local unless a ROM convention is shared across object families.

## Feature Design

Implementation proceeds in small evidence-backed passes:
1. Reproduce current WFZ focused tests and trace replay.
2. Convert provisional WFZ event behavior into tested ROM behavior: PLC requests, control lock, and state serialization.
3. Inspect the first WFZ trace divergence and identify the owning ROM routine from disassembly plus `target/trace-reports`.
4. Add the smallest failing test that represents the discovered ROM behavior.
5. Implement the fix with disassembly comments and rerun the focused trace plus regression guards.

Edge cases:
- WFZ has dual event dispatch each frame; tests must prove primary and secondary routines can both act on the same update.
- Rewind must restore WFZ BG scroll state and `WFZ_LevEvent_Subrout`, otherwise rewound gameplay can resume with the wrong background/cutscene phase.
- Tornado route bootstrap and cutscene logic must stay comparison-only in trace replay.

## Acceptance Tests

Focused:
- `mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic2.TestTodo12_WFZEventSpecs" test`
- `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestWFZBoss,com.openggf.game.sonic2.objects.TestTornadoObjectInstance" test`
- `mvn "-Dmse=off" "-Dsurefire.forkCount=1" "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"`

Regression guards:
- `mvn "-Dmse=off" "-Dsurefire.forkCount=1" "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s2.TestS2SczLevelSelectTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"`

## Risks

WFZ completion is broad enough that one pass may expose later unrelated parity frontiers. The stopping condition for a single implementation pass is either a green WFZ trace or a documented frontier advance with no regression.
