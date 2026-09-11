# OpenGGF Roadmap Proposal

This document is a proposal for how to prioritize OpenGGF after the `v0.4.20260304` release.

It is intentionally opinionated:

- Accuracy is more important than raw feature count.
- Shared engine maturity is more important than one-off hacks.
- A smaller number of release themes is better than a wide, vague backlog.

## Current Position

OpenGGF has crossed an important threshold:

- Sonic 1 is broadly playable from start to finish.
- Sonic 2 is the most complete game module (122/122 checklist objects, exact CNZ and WFZ
  Tornado traces) and includes ending/credits coverage.
- Sonic 3 & Knuckles has moved from early-game bring-up into near-complete vertical-slice
  stabilization; AIZ, HCZ, CNZ, MGZ, ICZ, MHZ, and LBZ have substantial coverage (about 57% of
  the object checklist, 134 unique ids), while FBZ and the S&K-half zones remain the largest
  content frontier and sit outside release-6 trace scope.
- Game Over / Continue screens are implemented in all three games; remaining
  timing details are tracked in `docs/status/known-bugs.md`.
- The engine now has stronger multi-game architecture, better tests, and a more credible release story than before `v0.4`.

That changes the planning problem. The project no longer needs a catch-all "prove this can work" roadmap. It needs a focused roadmap that turns broad momentum into a smaller number of high-value outcomes.

## Guiding Principles

### 1. Accuracy First

Gameplay, rendering, collision, audio, and event flow should continue to be validated against ROM behavior and disassembly references. "Looks close enough" should not be accepted as a default.

### 2. Shared Systems Before Workarounds

When multiple games need the same kind of capability, prefer improving shared providers, managers, and load pipelines instead of adding more game-specific branching in core flow.

### 3. Vertical Slices Over Shallow Coverage

A smaller number of fully coherent zones, cutscenes, and gameplay flows is more valuable than many partially implemented areas.

### 4. Release Discipline

Each release should have a narrow theme, explicit exit criteria, and documentation that matches reality.

## v0.5 Retrospective (2026-04-11)

### What the roadmap asked for

v0.5 was themed **"S3K Expansion and Shared-System Hardening"** with four priority areas:
S3K zone breadth, S3K runtime resource parity, shared load/lifecycle architecture, and release/CI
hygiene.

### What actually happened

The release became the largest modernization pass since the project was revived. It delivered:

**Delivered against v0.5 goals:**
- AIZ coverage is substantially deeper: AIZ miniboss defeat flow, signpost/results, Blue Ball
  entry/return, AIZ2 Flying Battery bombing sequence, AIZ2 end boss, post-boss capsule/cutscene
  flow, and the AIZ-to-HCZ transition are represented.
- HCZ is no longer just a load/render target: HCZ water rush, conveyor, fan, block, door, water
  skim, HCZ1 miniboss, and HCZ1-to-HCZ2 transition work landed.
- S3K bonus-stage coverage expanded across Gumball, Glowing Sphere/Pachinko, and Slots.
- S3K object/badnik coverage expanded with MegaChopper, Poindexter, Blastoid, Buggernaut, Bubbler,
  TurboSpiker, InvisibleHurtBlockH, CollapsingBridge, and related art/PLC wiring.
- Shared systems hardened significantly: two-tier services, GameRuntime ownership, LevelManager
  decomposition, MutableLevel, common base classes/utilities, trace replay infrastructure, and
  broader singleton lifecycle testing.

**Still incomplete after v0.5:**
- S3K is not yet a full-game path. Non-AIZ/HCZ zones still need object, event, scroll, boss, and
  PLC parity work.
- Bonus stages are active implementations, not pixel-perfect finished systems.
- The level editor foundation exists, but the editor remains a future release goal.

### Assessment

The shared-system hardening goal was exceeded. The S3K expansion goal was partially met for AIZ
depth but missed on zone breadth. The release pulled forward substantial v0.6 editor foundation
and v0.7 gameplay gap work that was not originally planned for v0.5.

---

## Proposed Priorities (Updated 2026-08-28)

## v0.6 Theme: S3K Playable Slice Parity and Release Readiness

The v0.6 scope has shifted from broad bring-up to release-grade stabilization of the playable
route slices already opened. The May target of applying shared architecture to S3K route closure
has produced more breadth than originally assumed: AIZ, HCZ, CNZ, MGZ, ICZ, MHZ, and parts of LBZ
now have meaningful object/event/trace coverage, and the project has a segmented S3K complete-run
trace suite. That suite is not green, but it gives the release line concrete frontiers instead of
vague "zone parity" goals.

The highest-value v0.6 work is now to keep the release surface honest: close or document known
trace blockers, keep release/CI guardrails strict, preserve the shared runtime architecture, and
avoid starting wide new zone work unless it directly advances a route slice or release blocker.

### Progress Since the May Roadmap

- S3K route work expanded beyond AIZ -> HCZ into CNZ, MGZ, ICZ, MHZ, and LBZ. Several route-critical
  objects, badniks, events, bosses/minibosses, palette/PLC paths, and scroll handlers now have
  focused tests.
- S3K complete-run trace coverage exists as per-zone trace replay tests, using a single Sonic+Tails
  AIZ -> Doomsday route segmented by zone. Current frontiers are explicit in
  `docs/status/trace-frontier-log.md` rather than hidden as informal blockers.
- The release line gained substantial hardening: trace policy gates, ROM-asset policy checks,
  editor/config/save recovery paths, native/shader packaging validation, branch-policy hooks, and
  architecture ratchets.
- The rewind framework became part of the active release surface rather than a side experiment:
  broad manager/object/level/registry snapshots, live-play rewind, audio/held-rewind fixes, and
  performance measurement now exist. Palette and determinism-audit work remains a near-term risk
  area.
- Data select/save is functionally in place for all three games through S3K-backed presentation and
  cross-game donation; remaining work is visual and parity polish, not core save plumbing.
- The editor is a config-gated experimental overlay with tile editing, undo/redo, persistence,
  and play-test round trips. It has been dormant since 2026-06-12 and is not a 0.6 user-facing
  feature.
- The managed test-session coordinator built mid-cycle was removed after it caused unbounded
  storage growth; builds, tests, guards, and trace evidence run direct Maven in each worktree.
  The lifecycle research continues as the separate v0.8 Actworks ask.
- Trace replay moved to a no-regression policy on 2026-08-26 (`docs/status/trace-scope-release-6.md`):
  release-6 scope is the `-Ptrace-replay` profile ({AIZ, HCZ, MGZ, CNZ, ICZ, LBZ} plus S1/S2
  fixtures); S&K-half zones including FBZ and all Knuckles routes are gated into
  `-Ptrace-replay-r7` and are expected red. A known-red trace may not worsen, and every green
  trace must stay green, but 0.6 no longer waits for the frontier to clear.
- The SMPS playback authenticity programme of 2026-08-21 was withdrawn on 2026-08-27 (commit
  `b4c8fbd8a`) because its fixes produced audible regressions the suite could not detect. It was
  then re-derived piecemeal between 2026-08-28 and 2026-09-04 against emulator-captured
  per-service driver-state and chip write-stream oracles, each a hard assertion, with every engine
  fix citing the driver routine it models (`README.md` "September 4 audio handover",
  `docs/changelog/v0.6-release-summary.md` Audio). Complete SMPS parity and release listening
  approval remain open; full-game parity is deferred to 0.7.
- Performance work has moved from generic cleanup to measured release work, including rewind/audio
  and rendering hot-path reductions with trace-equivalence checks.

### Primary Goals

- Make the opened S3K route slices reliable enough for a prerelease: AIZ/HCZ first, with CNZ, MGZ,
  ICZ, MHZ, and LBZ work prioritized by trace frontier and route impact.
- Treat complete-run trace frontiers as the main progress ledger for parity work. Move a frontier
  only by modelling ROM state, never by trace, frame, route, or zone carve-outs.
- Keep release-readiness guardrails green: trace policy, ROM-backed asset policy, architecture
  boundaries, editor/config/save resilience, and package validation.
- Use the runtime-owned framework stack where it directly supports the active slice:
  `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`,
  `ZoneLayoutMutationPipeline`, `ScrollEffectComposer`, `SpecialRenderEffectRegistry`, and
  `AdvancedRenderModeController`.
- Keep live rewind moving as an opt-in feature; the level editor stays dormant for 0.6, and
  neither outranks S3K route blockers, release gates, or trace determinism.

### Priority Areas

#### 1. S3K Route Slice Stabilization

- Keep AIZ -> HCZ as the primary release slice and continue using HCZ as the first continuation
  slice rather than a partial zone bring-up.
- Stabilize CNZ, MGZ, ICZ, MHZ, and LBZ where current branches/tests already opened the route.
- Defer broad FBZ/LRZ/SOZ/DEZ expansion unless it is needed for the release route or a
  low-risk shared-system fix. FBZ (zone id 4) is explicitly carved out of the release-6 trace
  profile despite sitting in the 0-6 id range.
- Use focused tests and stable-retro visual validation where practical.

#### 2. Trace Frontier Closure

- Use `docs/status/trace-frontier-log.md` as the canonical parity ledger for complete-run and level-select
  trace state.
- Prioritize first-divergence owners that block the opened route slices: sidekick CPU handoff,
  object slot/lifetime order, terrain/ring/hurt handoffs, boss/event transitions, and rewind-visible
  state.
- Keep trace fixes comparison-only. Do not hydrate gameplay state from trace rows in committed test
  or engine code.

#### 3. S3K Route-Impact Object Work

- Prioritize traversal blockers, terrain modifiers, hazards, boss/miniboss support, route-critical
  cutscenes, and high-usage badniks.
- Defer decorative or isolated objects until the target slice is playable.
- Use `S3K_OBJECT_CHECKLIST.md` as input to prioritization, not as the prioritization itself.
- The gaps listed in the June roadmap (AIZ drawbridge, HCZ large fan/block variants, MGZ
  launcher/pulley/boss work, LBZ boss/late-route work) have since landed. Remaining visible gaps
  are the ~43% of S3K checklist rows still unimplemented (mostly
  S&K-half and decorative objects), and the mostly unopened FBZ object set.

#### 4. Data Select and Save System

- S3K data select screen with ROM-accurate rendering, save slots, and team selection — **done**.
- Cross-game donation: S1/S2 can use S3K data select while retaining their own saves — **done**.
- Save persistence with JSON + SHA256 integrity hash — **done**.
- Runtime-generated S1/S2 zone previews and host emerald presentation — **done enough for v0.6**.
- Remaining: native selector mapping art, save-slot visual-state polish, and final emerald display
  parity.

#### 5. Level Editor Prototype (dormant)

- Config-gated editor/playtest loop with tile placement, world-grid navigation, undo/redo,
  persistence, and gameplay round trips exists as an experimental overlay.
- No editor work has landed since 2026-06-12 and none is planned for 0.6. Remaining: editing
  ergonomics, layer workflows, save UX, and S3K overlay compatibility.
- Editor fixes are release-relevant only when they protect teardown, saves, or mutable-level
  isolation. The modding framework the README describes is likewise planned, not started.

#### 6. Rewind and Determinism

- Keep live rewind safe as an opt-in debug/user feature: bounded history, stable object identity,
  audio correctness, and release-equivalent restore behavior.
- Close known rewind state holes that affect active S3K slices, especially palette color mutation
  and zone-event sidecar capture.
- Add or use determinism auditing to identify state missing from the rewind snapshot set; fix by
  modelling owned runtime state, not by replay special cases.

#### 7. S3K Runtime Resource Parity (continued)

- PLC, dynamic art, animated tile, palette, scroll, and render-mode improvements should follow the
  active route slices first.
- Reduce resource-reference warnings when they affect route visuals, gameplay clarity, trace
  determinism, or release packaging.

#### 8. ROM/Disassembly Tooling

- Better authoring and inspection workflows around objects, level data, and PLC data.
- Keep tooling focused on reducing ROM-address, object-table, trace-triage, and asset-intake
  mistakes during active S3K work.

### Suggested Exit Criteria for v0.6

- AIZ -> HCZ can be played as a coherent route slice with required traversal objects, event flow,
  scroll/parallax, animated tiles, palette/PLC state, transitions, and documented trace/visual
  validation status.
- CNZ, MGZ, ICZ, MHZ, and LBZ route blockers opened during v0.6 are either implemented or explicitly
  triaged with trace/frontier evidence.
- Complete-run trace replay status is documented for every included S3K segment, with no hidden
  known failures in release gating.
- The data select and save system is functional for all three games, with S3K as the primary presentation.
- The level editor is documented as a dormant experimental prototype, not a release feature.
- Live rewind is bounded, opt-in, and covered enough that it does not corrupt active gameplay,
  audio, level geometry, palette ownership, or object identity when enabled.
- S3K object coverage is broad enough in the active slices that zones feel playable, not just
  renderable.
- Release-readiness guard suites pass or have explicit, documented, non-hidden deferrals.
  Status at `develop` `2e06eb403` (2026-08-28): `-Pguards` 547/0/0 with no deferrals;
  ordinary suite 14,868 tests with 1 failure and 1 error under repair; `-Ptrace-replay`
  870 tests with the six known-red frontier failures, held under the no-regression policy.
- Trace replay meets the no-regression contract: every green trace stays green and no known-red
  trace worsens on the candidate. Clearing the frontier itself is not a 0.6 exit criterion.

## v0.7 Theme: Completion, Polish, and Parity Closure

This release should focus on reducing obvious gaps rather than introducing new strategic directions.

### Primary Goals

- Close high-visibility gameplay gaps across all three games.
- Improve end-to-end reliability of title, data select, save, special-stage, ending, and transition flows.
- Convert lingering TODO/FIXME areas into tested behavior or explicit deferrals.

### Priority Areas

- Game Over / Continue screens landed in 0.6; retain their input, save and restart coverage.
- The withdrawn SMPS and chip-level audio parity programme (retail service ordering, SFX
  priority, pause/resume, fades, regional clocks, YM write scheduling, S3K special-stage and
  SEGA PCM), re-landed with listening validation this time.
- Remaining high-value S3K gameplay gaps (additional zones, bosses, special stage polish),
  including moving the `-Ptrace-replay-r7` zones and Knuckles routes into scope.
- Special stage polish where current support exists but parity is incomplete.
- Final parity passes on transitions, boss sequences, and edge-case object behavior.
- Native **Knuckles in Sonic 2** support without S3K donation. Treat Sonic & Knuckles
  lock-on to Sonic 2 as an official patch target rather than a generic cross-game
  character option: check out the s2disasm Knuckles-in-Sonic-2 branch, document the
  branch-vs-stock Sonic 2 code/data differences, and implement Knuckles from those
  differences so physics, objects, monitors/life icons, title/level-select flow, and
  trace behavior are faithful to the lock-on game. Do not infer this behavior from
  S3K donation alone.
- Documentation cleanup around what is complete, partial, or intentionally deferred.

## v0.8 Tooling Ask: Actworks

Agent worktree and scratch-lifecycle research continues in the public
[`OpenGGF/Actworks`](https://github.com/OpenGGF/Actworks) project. Slipmat and
the lifecycle tools live there; [cowtree](https://github.com/raiscan/cowtree)
remains its own repository. OpenGGF has no runtime, build, test, or contributor
workflow dependency on Actworks. Any future reintroduction must prove bounded
storage, explicit lifecycle closure, and material benefit before adoption.

## 1.0 Criteria

Version `1.0` should not mean "every object from every game has been implemented."

It should mean:

- Sonic 1, Sonic 2, and Sonic 3 & Knuckles each have a credible full-game path.
- The engine can support long play sessions without major flow-breaking issues.
- Physics, collision, rendering, and audio regressions are heavily covered by automated tests.
- Remaining missing content is mainly content-completion work, not foundational architecture work.
- Contributors can understand the main extension points without reverse-engineering the entire codebase first.

## Explicit Non-Goals for the Near Term

These are all valid ideas, but they should not outrank the current roadmap themes:

- Broad new experimental features that bypass ROM accuracy concerns.
- Large UI rewrites that do not unlock gameplay, tooling, or reliability.
- Expanding many new zones at once without finishing the shared systems they depend on.
- Treating S1/S2 content growth as the main release driver while S3K remains structurally immature.

## Short Version

`v0.5.20260411` establishes the S3K AIZ-to-HCZ baseline and shared architecture hardening.
`v0.6` turns that baseline into release-hardened playable S3K route slices, with complete-run trace
frontiers as the main parity ledger under a no-regression policy. Data select/save is done, live
rewind is a real feature, the editor is dormant, the SMPS parity programme is deferred, and the
remaining work is to stabilize opened routes without hiding known failures. `v0.7` focuses on completion and parity closure; reusable agent
lifecycle research is a separate v0.8 Actworks ask.
