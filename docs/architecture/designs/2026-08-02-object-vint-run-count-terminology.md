# Object V-int run-count terminology

**Date:** 2026-08-02
**Status:** Implemented

## Context

`ObjectExecutionController` invokes every `ObjectInstance` with
`ObjectManager.vblaCounter()`:

```java
instance.update(objects.vblaCounter(), player);
```

That value is the object-visible Mega Drive V-int run count. It is deliberately de-phased
from `ObjectManager`'s executed-frame counter, and lag frames can make the two clocks
advance differently. Despite that contract, `ObjectInstance.update` and almost every
implementation call the first parameter `frameCounter`. The duplicate terminology has
repeatedly encouraged ROM gates such as `(Vint_runcount + 3) & 7` to be reasoned about as
ordinary frame gates.

The Java parser finds 809 exact two-argument object-update declarations across production
and tests. Of those, 808 use `frameCounter`; one uses `vblaCounter`. Four- and five-argument
`ObjectManager.update` overloads are not members of this set.

## Goals

- Name the object-update clock `vIntRunCount` at the interface and every exact
  `update(int, PlayableEntity)` implementation.
- Carry that terminology through framework hooks and private helpers whose corresponding
  argument is demonstrably sourced from the object-update clock.
- Rename stored values that exclusively retain the same clock, including boss
  `lastUpdatedFrame` state, to preserve provenance after the update method returns.
- Update generated object scaffolds, tests, Javadocs, and agent guidance so new code keeps
  the terminology.
- Add a durable guard against reintroducing `frameCounter`, `vblaCounter`, or another name
  at the object-update boundary.
- Prove the change is mechanical and behavior-neutral with compile, focused tests, and a
  full baseline comparison.

## Non-goals

- Renaming `ObjectManager.vblaCounter` itself. That field is the established storage owner;
  this change aligns consumers with the ROM-visible meaning without changing storage APIs.
- Renaming genuine executed-frame, animation-frame, results-screen, renderer, parallax,
  special-stage, or event counters.
- Changing `ObjectServices.resolveVIntRunCount`. Its trace-schema phase-offset behavior is
  still required; normal gameplay remains a pass-through.
- Changing object timing, trace hydration, queue authority, or ROM behavior.

## Design

### Canonical boundary

The canonical contract becomes:

```java
void update(int vIntRunCount, PlayableEntity player);
```

This applies to `ObjectInstance`, `InstaShieldHandle`, `BossChildComponent`, abstract base
classes, concrete production objects, nested object implementations, and test doubles.
The object scaffold emits the same name for its non-badnik `update` and both S1/S2 and S3K
badnik `updateMovement`/`updateAnimation` hook templates.

### Provenance propagation

The rename follows the value, not the token. A one-off Java rewrite runs javac attribution
over the complete main and test source sets, resolves declarations and references with
`Trees.getElement`, and identifies the exact two-argument object-update declarations. It
renames each parameter declaration and only those identifier uses bound to that parameter.
Nested, local, and anonymous scopes are therefore safe even when they shadow the old name.

The attributed rewrite seeds the object boundary and the framework's explicit
clock-forwarding hook families, including their resolved overrides:

- badnik `updateMovement` and `updateAnimation`;
- projectile `updateExtra`;
- boss `updateBossLogic`, child update, owner-managed-child hooks,
  `AbstractBossChild.beginUpdate`/`shouldUpdate`, and the nested defeat sequencer; and
- private helpers proven by the call-site analysis below.

Helper propagation is a symbol-resolved fixpoint. For each resolved callee formal, the tool
collects every project-source call site. It marks that formal as V-int-derived only when it
has at least one caller, every source reaching it is already V-int-derived, and the target
and argument position resolve unambiguously. A mixed-source, unresolved, reflective, or
externally callable formal is not renamed. The tool emits an ambiguity inventory for
manual classification rather than guessing. The validation report records the seed,
propagated, retained, and ambiguous sets.

State-field propagation uses the same all-sources rule across the complete attributed
source model: a field is renamed only when every
non-initialization project-source write resolves to a V-int-derived value and there is at
least one such write. A compile-time constant used solely as the clock's unset sentinel
(currently `-1`) is neutral rather than a second clock source. Test-only literal writes are
also permitted when they explicitly arrange the retained V-int clock for a fixture; their
field-bound references are renamed and the tests continue to describe those literals as
V-int values. Literal writes in production remain disallowed. In the boss framework,
`BossStateContext.lastUpdatedFrame` and
`AbstractBossChild.lastUpdatedFrame` store the object-update parameter and therefore become
`lastUpdatedVIntRunCount`. The attributed audit also proves four object fields exclusively
retain the same value: `TunnelbotBadnikInstance.globalFrameCounter`,
`CPZSpinTubeObjectInstance.currentFrameCounter`,
`EggPrisonObjectInstance.globalFrameCounter`, and
`RisingLavaObjectInstance.lastFrameCounter`. They become `vIntRunCount`,
`currentVIntRunCount`, `vIntRunCount`, and `lastVIntRunCount` respectively. Their
symbol-bound reads change with the declarations. The audit must also discover rather than
hand-pick every other retained field or identity-only local with the same provenance;
clock-bearing names such as `currentFrameCounter`, `lastFrameCounter`, `deleteFrame`, and
`...EnteredFrame` are in scope when all writes prove V-int identity. `AbstractResultsScreen.frameCounter` is
explicitly excluded: the separate `ResultsScreen.update(int, Object)` bridge supplies
frames-since-results-started, so that field has mixed provenance even though the object
entry path supplies V-int. Local aliases are
renamed only when their initializer and every later assignment preserve V-int identity;
mixed locals remain excluded.

### Trace compatibility

`ObjectServices.resolveVIntRunCount(int vIntRunCountAtObservation)` remains the single
owner of the legacy trace-schema phase offset. Call sites such as MGZ drilling Robotnik,
ICZ path platforms, and LBZ flame throwers continue to use it. Their input variables are
renamed to state what the resolver receives; the resolver is not removed or bypassed.

### Guard

A JUnit 5 source guard parses `src/main/java` and `src/test/java` with the JDK compiler API.
It selects methods named `update` with exactly two parameters, where the first type is
`int` and the second type is `PlayableEntity` (qualified or unqualified), and requires the
first parameter name to be `vIntRunCount`. It also enforces the name on the explicit
framework hook families and their overrides: `updateMovement`, `updateAnimation`,
`updateExtra`, `updateBossLogic`, `updateOwnerManagedChildren`, boss-child
`beginUpdate`/`shouldUpdate`, and the boss defeat sequencer's `update`. Finally, it requires
the canonical retained-clock field names for the complete attributed retained-field/local
inventory, and rejects their old declarations. The inventory is kept explicit in the guard
so a future ambiguous provenance change cannot silently weaken the naming contract.
The scaffold test separately requires generated source to use the canonical name.

The guard intentionally does not prohibit `frameCounter` globally. A global ban would
erase the distinction this work is meant to clarify and would reject legitimate clocks.

### Documentation

`AGENTS.md` and `CLAUDE.md` receive the same concise gotcha: object `update` receives
`V_int_run_count`, while `ObjectManager`'s executed-frame counter is a different clock.
The earlier handover design receives a supersession note rather than rewriting its
historical decision to defer the change. `README.md` receives the release-log summary
required for direct integration into `develop`.

## Alternatives considered

### Global textual replacement

Rejected. Thousands of legitimate `frameCounter` identifiers represent animation,
rendering, results, level, and executed-frame clocks. Replacing them would destroy useful
semantic distinctions and risk behavior changes.

### Rename only `ObjectInstance`

Rejected. Java parameter names are not inherited. Leaving implementations and generated
objects unchanged would preserve the source of confusion where agents do most ROM work.

### Introduce a clock wrapper type

Rejected for this change. A value type could enforce stronger compile-time provenance but
would change hundreds of signatures, arithmetic sites, and APIs beyond the requested
terminology correction. The medium-priority defect can be removed with a behavior-neutral
rename and a guard.

## Risks and controls

- **Concurrent edits:** use a dedicated worktree and integrate only after rebasing or
  merging the latest `develop` changes and resolving conflicts by intent.
- **Over-broad rewrite:** select methods from parsed syntax and follow only proven argument
  flow; review every non-object `frameCounter` diff and require none.
- **Missed override:** compile the entire project and run the parser guard over production
  and tests.
- **Rewind schema drift:** run focused rewind coverage, schema, and round-trip tests after
  renaming retained boss fields.
- **Fixture literals mistaken for another clock:** permit literal retained-clock writes only
  under `src/test/java`, inventory every one in validation, and reject production literals.
- **Behavior drift:** compare the full suite against the same updated `develop` baseline;
  parameter names must not change runtime logic.

## Acceptance criteria

- All exact `update(int, PlayableEntity)` declarations use `vIntRunCount`.
- All five object scaffold clock signatures generate `vIntRunCount`.
- Object clock-forwarding framework hooks, every proven retained field, and proven
  local aliases use V-int terminology.
- The provenance validation report records no unresolved ambiguity in the renamed set.
- Genuine frame counters outside the object clock dataflow remain unchanged.
- The terminology guard first fails on the old tree and passes after the rewrite.
- The project compiles, focused object/scaffold/architecture tests pass, and the full suite
  introduces no attributable regression relative to the updated integration baseline.
