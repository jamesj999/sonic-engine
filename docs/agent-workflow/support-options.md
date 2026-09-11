# Agent Workflow Support Options

This document captures high-level options for helping AI agents implement OpenGGF objects, levels, and trace fixes with less context loss and fewer repeated mistakes. It is intended as delegation material for external agents, not as a committed implementation plan.

## Problem Summary

Object and level work in this repository is deliberately rigorous. A correct change often requires ROM disassembly research, ROM offset verification, shared runtime framework integration, guard-test compliance, trace replay validation, and documentation policy updates. That protects ROM parity, but it creates workflow pain for agents:

- Agents spend a lot of time rediscovering the correct entry points, commands, and guard tests.
- S3K work has hidden hazards: S&K-side addresses only, dual object pointer tables, PLC/art overlap, and zone-set-specific object IDs.
- Object implementation touches many small integration surfaces: IDs, registries, art, mappings, PLC entries, collision/touch profiles, lifecycle helpers, child spawning, rewind state, and tests.
- Trace replay failures are powerful but hard to triage without reading generated reports, aux events, and disassembly side by side.
- Architectural guard tests fail late when an agent uses direct map mutation, singleton access in object code, constructor-time services, trace hydration, or unsupported JUnit/import patterns.
- Documentation and commit-trailer obligations are easy to miss during focused implementation work.

## Desired Outcome

Help agents start each task from a verified workflow, keep parity rules visible at the point of action, and turn CI/test failures into actionable next steps. The goal is not to loosen project standards. The goal is to make the standards easier to follow.

## Option 1: Agent Runbooks Per Work Type

Create concise, executable runbooks for the recurring high-risk workflows:

- Implement S3K object or badnik.
- Implement S1/S2 object or badnik.
- Bring up S3K zone feature work.
- Fix a trace replay divergence.
- Add ROM-backed art, mappings, or PLC registration.
- Add gameplay level mutation.

Each runbook should include:

- Exact discovery commands.
- Expected files to inspect and likely files to touch.
- Required guard tests and focused regression tests.
- Common failure signatures.
- ROM/disassembly citation expectations.
- Documentation and commit-trailer obligations.

Best first target: S3K object implementation. It has the most hidden integration steps and the highest chance of producing late art, PLC, lifecycle, or trace failures.

## Option 2: Preflight Checklist Generator

Add a small local CLI/tooling command that prints a task-specific checklist before an agent begins work.

Example shape:

```powershell
mvn exec:java "-Dexec.mainClass=com.openggf.tools.AgentWorkflowTool" "-Dexec.args=object s3k MHZ 0x8A"
```

For an object task, the tool could output:

- Game, zone, act, and S3K zone-set resolution.
- Object ID, primary object name, and registry status.
- Likely disassembly labels and file locations.
- `RomOffsetFinder` commands for object code, art, mappings, DPLCs, and PLCs.
- Existing implementation and test files that look related.
- Required guard tests.
- Suggested focused tests.
- Whether trace replay workflow is likely relevant.
- Documentation files likely affected.

This would prevent agents from starting with stale assumptions or incomplete discovery.

## Option 3: ROM Address And Art Intake Tool

Create a helper focused on the most error-prone asset path: ROM-backed object art and mappings.

For S3K, the helper should:

- Always search with `RomOffsetFinder --game s3k`.
- Reject Sonic 3 standalone addresses for S3K runtime constants.
- Identify whether a label result comes from `sonic3k.asm` or `s3.asm`.
- Surface mapping/art/PLC relationships.
- Recommend `StandaloneArtEntry` versus `LevelArtEntry`.
- Print candidate `Sonic3kConstants` names.
- Print candidate `Sonic3kPlcArtRegistry` registration hints.
- Suggest the required `TestSonic3kPlcArtRegistry` and `TestPatternSpriteRendererCorruptionGuard` runs.

This option directly targets sprite corruption, wrong-half addresses, bad mapping offsets, and PLC overlap mistakes.

## Option 4: Object Scaffold With Guard-Friendly Defaults

Provide a scaffold command or template for new object work. The scaffold should not guess behavior; it should create the boring integration shell so agents can spend attention on ROM parity.

Useful scaffold output:

- Object instance skeleton in the correct game package.
- Registry entry reminder or generated placeholder.
- Focused unit test shell.
- Art registry test shell if art/mapping/PLC files are involved.
- `ObjectServices`-safe constructor pattern.
- `spawnChild()` / `spawnFreeChild()` examples where child objects are expected.
- Comments or assertions around center-coordinate usage for ROM `x_pos` / `y_pos`.
- Hooks for `ObjectControlState`, `ObjectPlayerQuery`, `ObjectLifetimeOps`, and canonical solid/touch/lifecycle profiles where applicable.

The scaffold should default away from direct `setDestroyed(true)`, direct `ObjectManager.addDynamicObject(...)`, singleton access, and constructor-time service calls.

## Option 5: Trace Triage Assistant

Add a local diagnostic command that reads trace replay output and produces a short first-divergence brief.

Inputs:

- `target/trace-reports/<game>_<zone>_report.json`
- `target/trace-reports/<game>_<zone>_context.txt`
- Optional `src/test/resources/traces/.../aux_state.jsonl`

Output:

- First divergent frame and field.
- ROM versus engine values.
- Nearby object IDs, routines, subtypes, and positions if present.
- Likely owning subsystem: player physics, object solid, touch response, event, sidekick, palette, layout mutation, art/PLC, or test bootstrap.
- Suggested disassembly search terms.
- Suggested focused tests.
- Reminder that trace data is comparison-only and must not hydrate engine state.
- Whether `docs/status/trace-frontier-log.md` likely needs an update.

This would reduce the chance that agents jump to broad shared-code changes before identifying the first semantic mismatch.

## Option 6: Zone Analysis Spec Normalizer

The S3K zone-analysis workflow already exists, but its output can be made stricter and easier for follow-on agents to consume.

Normalize each zone analysis spec into a stable shape:

- Zone metadata and zone set.
- Events / Dynamic Resize.
- Parallax / Deform.
- Animated tiles / AniPLC.
- Palette cycling / AnPal.
- Palette mutations separate from palette cycling.
- Notable objects.
- Route blockers.
- Character-specific paths.
- Water, screen shake, boss gates, act transitions, and cutscene state.
- Confidence level per feature.
- Owning runtime framework per feature.
- Required tests and validation commands.

An external agent implementing one slice should be able to consume the spec without repeating the full disassembly pass.

## Option 7: CI Guard Failure Explainer

Create a document or small tool that maps architectural guard-test failures to concrete fixes.

Initial mappings:

- `TestNoDirectMapMutationsInGameplay` means gameplay code must use `ZoneLayoutMutationPipeline` or a `LevelMutationSurface`, not direct `getMap().setValue(...)`.
- `TestObjectServicesMigrationGuard` means object code must use injected `ObjectServices`, not singleton access.
- `TestNoServicesInObjectConstructors` and `TestConstructionContextGuard` mean constructors must not call `services()` unless all construction sites are properly wrapped by object construction context helpers.
- `TestTraceReplayInvariantGuard` means trace replay data must remain comparison-only; do not add committed trace-to-engine hydration.
- `TestTraceHydrateSwitchDefault` means diagnostic hydration must stay off by default.
- `TestSonic3kPlcArtRegistry` means art, mapping, frame dimensions, tile index, or PLC registration likely needs correction.
- `TestPatternSpriteRendererCorruptionGuard` means sprite renderer or mapping output may permit pathological frame geometry.
- JUnit migration guards mean new tests must use JUnit 5 / Jupiter only.

This makes guard failures actionable without weakening the guard baselines.

## Option 8: Pitfall Catalogue Index

The `rom-pitfalls.md` files are valuable but easy to miss during implementation. Create a generated or manually maintained index organized by bug class.

Suggested categories:

- Moving solid timing.
- Touch response polling.
- Enemy versus special contact edge behavior.
- Child object spawning.
- Offscreen lifecycle and remembered spawn state.
- Player and sidekick participation.
- Object control bits.
- Center-coordinate versus top-left-coordinate usage.
- Native playable-sprite position writes.
- PLC, art, mappings, DPLC, and virtual pattern IDs.
- S3K zone-set resolution.
- S3K S&K-side versus Sonic 3 standalone address confusion.
- Dynamic resize, AniPLC, and palette interactions.

The index should point to the relevant per-game pitfall entries and cite the originating tests or commits where available.

## Option 9: Documentation Obligation Checklist

Create a short checklist that agents run before finalizing object, level, or trace changes.

It should ask:

- Did a trace frontier move, regress, or get used for target selection? If yes, update `docs/status/trace-frontier-log.md`.
- Did an object/badnik trace fix reveal a reusable pitfall? If yes, update the relevant `.agents/skills/.../rom-pitfalls.md` and mirrored `.claude/skills/.../rom-pitfalls.md`.
- Did engine behavior under `src/main/` change in a changelog-worthy way? If yes, update `CHANGELOG.md`; otherwise justify the skip in the commit trailer.
- Did known discrepancies change? If yes, update the relevant discrepancies doc.
- Did configuration behavior change? If yes, update `CONFIGURATION.md`.
- Did agent guidance or skills change? If yes, use the `Skills: updated` trailer.

This option targets the common problem of technically correct changes failing branch policy or losing institutional knowledge.

## Option 10: External-Agent Delegation Prompt Templates

Create prompt templates for delegating bounded research or implementation tasks to external agents.

Useful templates:

- Disassembly research agent: find routines, labels, constants, art, mappings, PLCs, subtype behavior, and cite exact files/lines.
- Object implementation agent: implement only after consuming a research brief and checking guard rules.
- Trace triage agent: summarize first divergence and likely owner without editing code.
- Art verification agent: inspect mappings, dimensions, tile ranges, and PLC relationships.
- Review agent: review for ROM parity, guard-rule compliance, tests, and docs.

Each template should include non-negotiable project rules:

- Use ROM-backed runtime assets only.
- Do not use S3 standalone addresses for S3K locked-on runtime work.
- Do not add zone, route, or frame carve-outs for trace fixes.
- Do not hydrate engine state from trace data.
- Do not use game-name branches for physics divergences.
- Preserve center-coordinate semantics for ROM positions.
- Use injected object services in object code.

## Recommended Implementation Order

Start with low-risk support that external agents can use immediately:

1. CI guard failure explainer.
2. S3K object implementation runbook.
3. External-agent delegation prompt templates.
4. Documentation obligation checklist.

Then add tooling:

1. Preflight checklist generator.
2. ROM address and art intake helper.
3. Trace triage assistant.
4. Zone analysis spec normalizer.
5. Object scaffold with guard-friendly defaults.
6. Pitfall catalogue index.

This order gives agents better instructions before adding new code, then automates the workflows that prove most repetitive.

## Progress Tracking

Use this section as the shared status board when delegating work. External agents should update only their assigned row unless explicitly asked to coordinate the whole document.

Status values:

- `Not Started` means no owner has begun.
- `Researching` means an agent is gathering repo/disassembly/tooling context.
- `Designing` means the scope and acceptance criteria are being refined.
- `Implementing` means files are being changed.
- `Blocked` means progress needs user input or another dependency.
- `Ready For Review` means the agent believes the option is complete and has evidence.
- `Done` means the change has been reviewed and accepted.

| ID | Option | Priority | Status | Owner | Dependencies | Evidence / Notes |
|----|--------|----------|--------|-------|--------------|------------------|
| 1 | Agent runbooks per work type | P0 | Ready For Review | AI dynamic workflow | Existing skills and AGENTS docs | `docs/agent-workflow/runbooks/` — README + 6 runbooks (S3K object [primary], S1/S2 object, S3K zone feature, trace divergence, ROM art/mappings/PLC, gameplay level mutation). S3K runbook calls out zone-set, S&K-side addresses, ROM-only assets, center coords, ObjectServices. Paths verified against repo. |
| 2 | Preflight checklist generator | P1 | Ready For Review | AI dynamic workflow | Runbook shape from Option 1 | `com.openggf.tools.AgentWorkflowTool` + `TestAgentWorkflowTool` (19 tests pass). Matches `object s3k MHZ 0x8A`; resolves MHZ→0x07→SKL and the S3KL/SKL/S3L object spaces; resolves all real zones incl. HPZ/competition/bonus (flagged non-main). Known names report `NAME_KNOWN_FACTORY_UNVERIFIED` (not a false `PLACEHOLDER_ONLY`). Pure logic ROM-free; prints `RomOffsetFinder` commands rather than reading ROM. |
| 3 | ROM address and art intake tool | P1 | Ready For Review | AI dynamic workflow | `RomOffsetFinder`, S3K PLC/art tests | `com.openggf.tools.RomArtIntakeTool` + `TestRomArtIntakeTool` (17 tests pass). Wraps `RomOffsetFinder` (`GameProfile.sonic3k()`); the CLI rejects/flags `s3.asm`-sourced labels (Sonic 3 standalone / S3L half) — it classifies by source file, since a label search carries no ROM offset. The complementary `>=0x200000` address rule is a unit-tested helper (`isAcceptableForS3kRuntime`/`halfForAddress`) for resolved offsets. Recommends StandaloneArtEntry vs LevelArtEntry; processes multiple labels; suggests TestSonic3kPlcArtRegistry + corruption guard. |
| 4 | Object scaffold with guard-friendly defaults | P2 | Ready For Review | AI dynamic workflow | Options 1-3 recommended first | `com.openggf.tools.ObjectScaffoldTool` + `TestObjectScaffoldTool` (20 tests pass). Pure `generateInstance`/`generateTest`; `--game s3k --badnik` → `sonic3k.objects.badniks` + `AbstractS3kBadnikInstance`; no `getInstance()`/ctor `services()`/`addDynamicObject`/`setDestroyed(true)`; center-coord note; "behavior must be filled in from the disassembly, not guessed." Generated output compiles. |
| 5 | Trace triage assistant | P1 | Ready For Review | AI dynamic workflow | Trace reports and trace skill workflow | `com.openggf.tools.TraceTriageTool` + `TestTraceTriageTool` (15 tests pass). Reads `target/trace-reports/<game>_<zone>_report.json` (+context/aux) via existing Jackson against real `DivergenceReport` schema; emits first-divergence brief, subsystem classifier, comparison-only reminder. |
| 6 | Zone analysis spec normalizer | P2 | Ready For Review | AI dynamic workflow | Existing `s3k-zone-analysis` output | `com.openggf.tools.ZoneSpecNormalizerTool` + `TestZoneSpecNormalizerTool` (6 tests pass). Pure `normalize(String)`→13 canonical ordered sections; palette cycling vs mutation kept separate; `(not analyzed)` placeholders for gaps. |
| 7 | CI guard failure explainer | P0 | Ready For Review | AI dynamic workflow | Guard test inventory | `docs/agent-workflow/ci-guard-failure-explainer.md` — per-guard sections for all required guards + ~20 more; each with path, enforcement, symptom, correct fix; prominent "do not expand baselines" warning. All cited test paths confirmed to exist. |
| 8 | Pitfall catalogue index | P2 | Ready For Review | AI dynamic workflow | Existing `rom-pitfalls.md` files | `docs/agent-workflow/pitfall-catalogue-index.md` — groups existing S2 (P1–P41) / S3K (P1–P18) pitfalls by 13 bug classes; links source files + commits; notes cross-game applicability; flags no S1 pitfalls file; no invented entries. |
| 9 | Documentation obligation checklist | P0 | Ready For Review | AI dynamic workflow | Branch documentation policy | `docs/agent-workflow/documentation-obligation-checklist.md` — trailer→file map verified against `.githooks/validate-policy.sh` (corrected Guide→`docs/guide/`, Agent-Docs→AGENTS.md+CLAUDE.md); required vs justified-skip per item; TRACE_FRONTIER_LOG as policy-only. |
| 10 | External-agent delegation prompt templates | P0 | Ready For Review | AI dynamic workflow | This document and AGENTS rules | `docs/agent-workflow/delegation-prompt-templates.md` — 5 templates (disasm research, object impl, trace triage, art verification, review); shared non-negotiable rule block; output formats route results back into this tracking table. |

## Tracking Rules

- Keep updates concise: status, owner, blocking dependency, and evidence.
- Link to created files, commands run, failing tests, passing tests, or review notes in the `Evidence / Notes` column.
- Do not mark an item `Done` without review or a clear acceptance note.
- If an option changes scope, update its acceptance criteria below before implementation continues.
- If a task produces a new reusable workflow rule, update the relevant runbook, checklist, or pitfall index instead of leaving the knowledge only in a chat.
- If implementation touches agent skills, mirror required changes between `.agents/skills/` and `.claude/skills/` where the project already keeps mirrored skill files.

## Acceptance Criteria By Option

### Option 1: Agent Runbooks Per Work Type

- At least one runbook exists for S3K object implementation.
- The runbook includes discovery commands, files to inspect, files likely touched, required tests, and documentation obligations.
- The runbook explicitly calls out S3K zone-set resolution, S&K-side ROM addresses, ROM-only runtime assets, center-coordinate semantics, and object-service rules.

### Option 2: Preflight Checklist Generator

- A command can produce a task-specific checklist for at least one object workflow.
- Output includes registry status, zone-set resolution, likely disassembly labels, test suggestions, and documentation reminders.
- The command is covered by focused tests or deterministic golden-output checks.

### Option 3: ROM Address And Art Intake Tool

- The tool uses or wraps `RomOffsetFinder` rather than duplicating offset logic.
- S3K standalone Sonic 3 addresses are rejected or flagged loudly.
- Output includes art/mapping/PLC relationship hints and required art-corruption guard tests.

### Option 4: Object Scaffold With Guard-Friendly Defaults

- Generated scaffolds avoid singleton access, constructor-time `services()` usage, direct dynamic object insertion, and direct destruction/lifecycle shortcuts.
- Scaffold output includes a focused test shell.
- Scaffold text clearly says behavior must be filled from disassembly, not guessed.

### Option 5: Trace Triage Assistant

- The assistant reads existing trace report artifacts and identifies the first divergence.
- Output names the likely owning subsystem and suggested next files/search terms.
- It includes an explicit warning that trace data is diagnostic-only and must not write back into engine state.

### Option 6: Zone Analysis Spec Normalizer

- Zone specs use a consistent section layout for events, parallax, AniPLC, AnPal, notable objects, route blockers, confidence, owners, and tests.
- Palette cycling and palette mutation are represented separately.
- Follow-on agents can implement a feature category from the normalized spec without repeating the full analysis.

### Option 7: CI Guard Failure Explainer

- A document or tool maps the major guard tests to likely causes and correct fixes.
- It includes at least the direct map mutation, object services, constructor services, trace invariant, S3K art registry, sprite corruption, and JUnit 5 guards.
- It discourages baseline expansion unless explicitly justified.

### Option 8: Pitfall Catalogue Index

- The index groups existing pitfalls by bug class.
- Entries link back to the source pitfall file and relevant tests or commits where available.
- It calls out cross-game applicability when a pitfall is not game-specific.

### Option 9: Documentation Obligation Checklist

- The checklist covers trace frontier logs, pitfall updates, changelog/discrepancy/config docs, skill mirrors, and commit trailers.
- It distinguishes required updates from justified skips.
- It is short enough to run mentally before finalizing a branch.

### Option 10: External-Agent Delegation Prompt Templates

- Templates exist for disassembly research, object implementation, trace triage, art verification, and review.
- Each template includes the non-negotiable project rules relevant to that task.
- Templates define the expected output format so results can be merged into this tracking document or follow-on implementation plans.

## Success Criteria

These options are working if:

- Agents begin object and zone tasks with the right files, commands, and tests.
- Wrong S3K ROM-half addresses are caught before code review.
- Art/mapping/PLC corruption is caught by focused tests before manual playtesting.
- Trace replay fixes identify the first semantic divergence before shared code changes.
- Guard-test failures produce targeted fixes rather than baseline expansion.
- `docs/status/trace-frontier-log.md`, pitfall catalogues, changelog entries, and commit trailers stay current.
- External agents can perform bounded research or implementation without needing the original chat context.
