# External-Agent Delegation Prompt Templates

Ready-to-copy prompts for delegating bounded OpenGGF tasks to an external agent that
has **no chat context**. Copy one template, fill the `<...>` placeholders, and paste it
as the agent's full instructions. Each template embeds the non-negotiable project rules
relevant to that task and defines an **OUTPUT FORMAT** whose result merges directly into
`docs/agent-workflow/support-options.md` (the tracking doc) or a follow-on plan.

This is Option 10 of `docs/agent-workflow/support-options.md`. See also:
- `docs/agent-workflow/support-options.md` — tracking board (update only your assigned row).
- Skills under `.agents/skills/` and `.claude/skills/` (mirrored): `s3k-disasm-guide`,
  `s3k-implement-object`, `s3k-implement-boss`, `s3k-zone-analysis`,
  `trace-replay-bug-fixing`, `plc-system`, `s3k-plc-system`.
- Pitfall catalogues: `.agents/skills/s3k-implement-object/rom-pitfalls.md`,
  `.agents/skills/s2-implement-object/rom-pitfalls.md`.

---

## Shared Non-Negotiable Rules (RULE BLOCK)

Every template below references this block. When pasting a template, paste this block with
it. These come from `CLAUDE.md` / `AGENTS.md` / `AGENTS_S3K.md` and are enforced by guard
tests.

```
NON-NEGOTIABLE PROJECT RULES
1. ROM-only runtime assets. Object art, mappings, DPLCs, animation scripts, PLC data, and
   any runtime asset BYTES must come from the user-supplied ROM via the engine loader.
   NEVER read asset bytes from docs/ disassembly. docs/ is for research, labels, and
   offset DISCOVERY only.
2. S3K addresses: prefer S&K-side. Use sonic3k.asm offsets (< 0x200000); when both halves
   match, pick the sonic3k.asm hit. Run RomOffsetFinder with --game s3k. If only s3.asm hits
   return, re-search with label variants first; but if an object has genuinely no S&K
   equivalent it may reference the s3.asm (S3-half) asset directly — use it after verifying,
   rather than looping forever (rare edge case).
3. No carve-outs. Trace/physics fixes must model real ROM state (object id/routine,
   status/control bits, event flags, frame-counter visibility, physics profile,
   data-driven condition). NEVER branch on zone id/name, trace route, frame number, or a
   "known failing trace" exception. "ROM default except in AIZ" is still a carve-out.
4. No game-name physics branches. Cross-game divergences use the smallest accurate owner
   from `docs/architecture/per-game-rule-placement.md`, NEVER `if (gameId == GameId.S3K)`.
5. Trace data is comparison-only. Never hydrate/sync engine state FROM trace data in
   committed test code. The property oggf.trace.hydrate must stay unset.
6. Injected ObjectServices in object code. Call services() (or tryServices()); NEVER
   getInstance(); NEVER call services() in a constructor (defer to lazy init / first
   update()). Use spawnChild(...) / spawnFreeChild(...) for child objects.
7. Center-coordinate semantics. ROM positions use center coords: getCentreX()/getCentreY()
   and NativePositionOps for native x_pos/y_pos writes — NOT top-left getX()/getY().
8. Level mutations route through ZoneLayoutMutationPipeline / a LevelMutationSurface in
   gameplay paths — never a direct getMap().setValue(...).
9. Tests are JUnit 5 / Jupiter ONLY. No org.junit.* (JUnit 4) imports, rules, or runners.
10. If you create or modify ANY skill file, mirror the identical change in BOTH
    .claude/skills/<name>/ AND .agents/skills/<name>/. Prefer standalone docs under
    docs/agent-workflow/ over mutating skills.
11. Do NOT run `git add -A` / git commit. The working tree is shared; edit only your
    assigned files. Source files end with a trailing newline. Java 21.
```

---

## Template 1 — Disassembly Research Agent

Use to find routines, labels, constants, art/mapping/DPLC/PLC addresses, and subtype
behavior, and cite exact files/lines. **Research only — produces a brief, edits no code.**

```
ROLE: Disassembly research agent for the OpenGGF engine (repo root:
C:/Users/farre/IdeaProjects/sonic-engine). You have no chat context. Read this whole prompt.

TASK: Research <SUBJECT — e.g. "S3K object 0x5C (AIZ Hollow Tree)"> in the disassembly and
produce a research brief with exact ROM addresses, labels, and file/line citations. Do NOT
write engine code. Do NOT modify any file except (optionally) a new brief under
docs/agent-workflow/research/ if explicitly asked.

REFERENCES:
- S3K disasm: docs/skdisasm/  (S&K half = sonic3k.asm). S2: docs/s2disasm/. S1: docs/s1disasm/.
- Tool: com.openggf.tools.disasm.RomOffsetFinder. Run with --game s1|s2|s3k. Subcommands:
  search, verify, find, test, plc. See the s3k-disasm-guide / s2disasm-guide / s1disasm-guide
  skills for the full command catalog.
- Constants land in: Sonic3kConstants.java / Sonic2Constants.java / Sonic1* constants.

RULES (see RULE BLOCK): 1 (docs/ is research-only — you ARE allowed to read it for labels and
offsets, but the engine must later load asset BYTES from the ROM, not from docs/), 2 (S&K-side
addresses ONLY for S3K; verify against machine code, not just labels), 11.
For S3K: when a label resolves in both sonic3k.asm and s3.asm, pick the sonic3k.asm hit.
Verify each address against the actual ROM machine code where possible (e.g. the
`move.l #addr, $0C(a0)` art-tile load), not just the disasm label, per AGENTS_S3K.md.

OUTPUT FORMAT (Markdown; this merges into a follow-on implementation plan):
## Research Brief: <SUBJECT>
- Game / Zone-set: <s3k | s2 | s1> / <S3KL | SKL | n/a>
- Object/Routine id(s): <hex ids and ROM routine labels>
### Source citations
| What | Disasm file:line | Label |
|------|------------------|-------|
| main routine | docs/skdisasm/sonic3k.asm:<line> | <label> |
### ROM addresses (S&K-side for S3K)
| Constant name | Address (hex) | Compression | Verified by |
|---------------|---------------|-------------|-------------|
### Art / mappings / DPLC / PLC
- art_tile base, palette line, mapping addr, DPLC addr (or "none"), PLC entry.
- make_art_tile() call cited with file:line.
### Behavior summary
- Subtypes, routine state machine (stages + triggers), control bits, player participation,
  movement order (move-before-gravity vs gravity-before-move), collision size index.
### Open questions / LOW-confidence items
- <anything needing ROM verification before implementation>
### RomOffsetFinder commands run
- <exact commands + results>
```

---

## Template 2 — Object Implementation Agent

Use to implement an object/badnik **only after** a research brief exists. Embeds the object
guard rules.

```
ROLE: Object implementation agent for OpenGGF (repo root:
C:/Users/farre/IdeaProjects/sonic-engine). No chat context.

PRECONDITION: You MUST consume a completed research brief (Template 1 output) before writing
code. If no brief is supplied, STOP and request it. Do not guess behavior from the object name.

TASK: Implement <OBJECT — id, name, zone-set> as an ObjectInstance. Match the ROM exactly.

REQUIRED READING (skill, mirrored under .agents/skills/ and .claude/skills/):
- s3k-implement-object (or s2-/s1-implement-object). Read its rom-pitfalls.md cover to cover:
  .agents/skills/s3k-implement-object/rom-pitfalls.md (18 patterns P1-P18) or
  .agents/skills/s2-implement-object/rom-pitfalls.md (21 patterns).

REUSE BEFORE REIMPLEMENTING (do NOT roll your own — all in com.openggf.level.objects unless noted):
- SubpixelMotion (16:8 fixed-point moves), PatrolMovementHelper, PlatformBobHelper,
  SpringBounceHelper, DestructionEffects, WaypointPathFollower, ObjectControlledSolidContactController.
- Base classes: AbstractObjectInstance, AbstractBadnikInstance, AbstractProjectileInstance,
  AbstractMonitorObjectInstance, AbstractPointsObjectInstance. S3K badniks:
  com.openggf.game.sonic3k.objects.badniks.AbstractS3kBadnikInstance.
- Behavior contracts: ObjectControlState, ObjectPlayerQuery / ObjectPlayerParticipationPolicy,
  NativePositionOps (com.openggf.sprites), ObjectLifetimeOps, profiles in
  com.openggf.game.profiles.* (e.g. ObjectLifecycleProfile).
- Art: Sonic3kObjectArt.buildLevelArtSheetFromRom(mappingAddr, artTileBase, palette);
  register the plan entry in Sonic3kPlcArtRegistry; keys in Sonic3kObjectArtKeys; addresses in
  Sonic3kConstants. Register the factory in Sonic3kObjectRegistry.registerDefaultFactories()
  via factories.put(id, (spawn, registry) -> ...). Add ids to Sonic3kObjectIds.

RULES (see RULE BLOCK): 1, 2, 4, 6, 7, 8, 9, 11. Specifically:
- ROM-only art bytes (rule 1). S&K addresses only (rule 2).
- services() not getInstance(); never services() in constructor (rule 6) — guarded by
  TestObjectServicesMigrationGuard, TestNoServicesInObjectConstructors,
  TestConstructionContextGuard. Wrap runtime `new Child(...)` in setConstructionContext/
  clearConstructionContext or use spawnChild()/spawnFreeChild().
- Center coords for player interaction/kill checks (rule 7).
- Route tile edits through services().zoneLayoutMutationPipeline() (rule 8) — guarded by
  TestNoDirectMapMutationsInGameplay.
- No game-name branches (rule 4); no zone carve-outs (rule 3) — model control bits/subtype.
- ENEMY touch responses poll every frame; do NOT add "already hit" latches.

TESTS: Add a focused JUnit 5 test (HeadlessTestRunner where physics is involved; StubObjectServices
for service doubles). Keep these green: TestSonic3kPlcArtRegistry, TestS3kAiz1SkipHeadless,
TestSonic3kLevelLoading. Build/test commands (PowerShell, quote -D props):
  mvn package
  mvn "-Dtest=com.openggf.game.sonic3k.TestSonic3kPlcArtRegistry" test
  mvn "-Ds3k.rom.path=s3k.gen" test

OUTPUT FORMAT (Markdown):
## Implementation Report: <OBJECT>
- Files created/edited (absolute paths) + one-line purpose each.
- ROM addresses used (table) and the brief they came from.
- Behavior contracts / helpers reused (list) — and anything NOT reused, with reason.
- Guard tests considered (list) and how the code stays compliant (no baseline expansion).
- Tests added (class + what they assert) and the exact mvn command + pass/fail.
- Known discrepancies (candidate entries for docs/S3K_KNOWN_DISCREPANCIES.md).
- LOW-confidence areas needing review.
```

---

## Template 3 — Trace Triage Agent

Use to summarize the first divergence in a `*TraceReplay` failure and name the likely owner.
**Diagnosis only — never edits engine state or trace data.**

```
ROLE: Trace triage agent for OpenGGF (repo root: C:/Users/farre/IdeaProjects/sonic-engine).
No chat context. You DIAGNOSE ONLY — you do not fix code and you do not modify any file.

TASK: Identify the FIRST semantic divergence in <TRACE/TEST — e.g. "MZ1 TraceReplay"> and name
the likely owning subsystem, with suggested next files/search terms.

REQUIRED READING: trace-replay-bug-fixing skill (mirrored .agents/skills/ + .claude/skills/).
Pipeline: recorder -> parser -> comparator; replay tests extend AbstractTraceReplayTest.

RULES (see RULE BLOCK): 3 (no carve-outs — the eventual fix must model ROM state, so frame your
analysis around object id/routine/status bits/event flags/profile, NOT zone/route/frame), 5
(trace data is COMPARISON-ONLY: never propose hydrating engine state from the trace; the property
oggf.trace.hydrate must stay unset — guarded by TestTraceReplayInvariantGuard and
TestTraceHydrateSwitchDefault), 11.
Do NOT propose a zone/route/frame "exception" as a fix.

OUTPUT FORMAT (Markdown — merges into docs/status/trace-frontier-log.md and a follow-on plan):
## Trace Triage: <TRACE>
- Command run + commit/worktree context.
- Pass/fail, total error count.
- FIRST diverging frame / field: frame=<n>, field=<x_pos|speed|angle|camera|rings|...>,
  expected=<v>, actual=<v>, delta=<d>.
- Likely owning subsystem: <physics | collision | object routine | event manager | camera |
  parallax | palette | ...>.
- ROM state hypothesis driving the branch (object id/routine, status/control bits, event flag,
  physics profile, data-driven condition). NO zone/route/frame carve-out.
- Suggested next files to inspect (absolute paths) and search terms.
- Explicit note: this is diagnostic; the fix must NOT hydrate engine state from the trace and
  must NOT add a zone/route/frame carve-out.
```

---

## Template 4 — Art Verification Agent

Use to inspect mappings, dimensions, tile ranges, palettes, and PLC relationships for a
registered art entry. Catches corruption before playtesting.

```
ROLE: Art verification agent for OpenGGF (repo root: C:/Users/farre/IdeaProjects/sonic-engine).
No chat context.

TASK: Verify the art/mapping/PLC for <OBJECT/ART KEY — e.g. "Sonic3kObjectArtKeys.AIZ1_TREE">
is ROM-accurate and within sane bounds. Confirm the Sonic3kPlcArtRegistry plan entry, mapping
frame/piece counts, tile ranges, palette line, and DPLC/PLC relationship.

REFERENCES:
- Sonic3kPlcArtRegistry (ZoneArtPlan: StandaloneArtEntry + LevelArtEntry; getPlan(zoneId, actId)).
- Sonic3kObjectArt.buildLevelArtSheetFromRom(...); Sonic3kObjectArtKeys; Sonic3kConstants.
- PLC: plc-system / s3k-plc-system skills.
- Guard test TestSonic3kPlcArtRegistry enforces sane bounds:
  MAX_SANE_FRAMES=256, MAX_SANE_FRAME_PIECES=80, MAX_SANE_FRAME_TILES=512,
  MAX_SANE_FRAME_SPAN_PIXELS=1024, MAX_SANE_ABS_PIECE_OFFSET_PIXELS=2048.
- Renderer guard: PatternSpriteRenderer suppresses frames with pieceCount > 80
  (TestPatternSpriteRendererCorruptionGuard).

RULES (see RULE BLOCK): 1 (mappings/DPLC/PLC bytes come from the ROM, never docs/), 2 (S&K-side
S3K addresses only), 11.

OUTPUT FORMAT (Markdown):
## Art Verification: <ART KEY>
- Registry entry: zone/act, StandaloneArtEntry|LevelArtEntry, key, mappingAddr, artTileBase/artAddr,
  palette line, dplcAddr, compression. Cite Sonic3kPlcArtRegistry line(s).
- ROM addresses cross-checked against disasm make_art_tile() / mapping label (cite file:line).
- Frame audit: frame count, max pieces/frame, tile index range, span px, max abs piece offset px —
  PASS/FAIL against the bounds above.
- DPLC/PLC relationship: which PLC loads the art, VRAM tile base, any overlap with shared art.
- Verdict: OK | corrupt | wrong palette | tile out of range | s3.asm address used (FIX) | needs ROM verify.
- Recommended registry fix (if any) — exact field + value.
```

---

## Template 5 — Review Agent

Use to review a completed change for ROM parity, guard-rule compliance, tests, and docs.

```
ROLE: Review agent for OpenGGF (repo root: C:/Users/farre/IdeaProjects/sonic-engine). No chat
context. Review the supplied diff/branch; do not rewrite it — report findings.

TASK: Review <CHANGE — branch / file list> for ROM parity, guard-rule compliance, test coverage,
and documentation obligations.

CHECK AGAINST RULE BLOCK (cite the specific rule + guard test for each finding):
1  ROM-only assets — no asset bytes read from docs/.
2  S3K: prefer S&K-side; s3.asm reference only when no S&K equivalent exists (rare; verify).
3  No zone/route/frame/"known failing trace" carve-outs — branches model ROM state.
     Guard: source review + trace guards.
4  No game-name physics branches — use the smallest accurate owner from
   `docs/architecture/per-game-rule-placement.md`. Guard: TestArchitecturalSourceGuard.
5  Trace comparison-only, oggf.trace.hydrate unset. Guards: TestTraceReplayInvariantGuard,
     TestTraceHydrateSwitchDefault.
6  services() not getInstance(); no services() in constructors; child spawn wrapped. Guards:
     TestObjectServicesMigrationGuard, TestNoServicesInObjectConstructors, TestConstructionContextGuard.
7  Center coordinates for ROM positions (getCentreX/Y, NativePositionOps). Guard: TestArchitecturalSourceGuard.
8  Tile edits route through ZoneLayoutMutationPipeline. Guard: TestNoDirectMapMutationsInGameplay.
9  JUnit 5 only. Guards: TestJunit5MigrationGuard, TestArchitecturalReviewGuard.
DOCS (Branch Documentation Policy): commit-message trailers each set to `updated` or `n/a`:
  Changelog (CHANGELOG.md), Guide + Agent-Docs (AGENTS.md + CLAUDE.md),
  Known-Discrepancies (docs/status/known-discrepancies.md),
  S3K-Known-Discrepancies (docs/S3K_KNOWN_DISCREPANCIES.md),
  Configuration-Docs (CONFIGURATION.md), Skills (.agents/skills/* + .claude/skills/* — must be mirrored).
  Trace frontier work must update docs/status/trace-frontier-log.md. A feat/fix/perf commit touching
  src/main/ must set Changelog: updated or justify with `Changelog: n/a: <reason>`.

GUIDANCE: Flag baseline expansion of any guard test unless the change documents an explicit,
specific reason. Prefer targeted fixes over allowlist growth.

OUTPUT FORMAT (Markdown):
## Review: <CHANGE>
### Blocking findings
| # | Rule/Guard | File:line | Issue | Required fix |
### Non-blocking suggestions
| # | File:line | Suggestion |
### Tests
- New/changed tests present? JUnit 5? Headless where physics? Suggested mvn command + expected result.
### Documentation obligations
| Trailer | File | Required? | Present? | Note |
### Verdict
- Approve | Approve-with-nits | Request-changes, with a one-paragraph rationale.
```

---

## Merging Results Back

- **Tracking board:** paste the agent's OUTPUT FORMAT result into the relevant row's
  "Evidence / Notes" cell of the Progress Tracking table in
  `docs/agent-workflow/support-options.md`, and set the row Status
  (`Researching` / `Implementing` / `Ready For Review` / `Done`). Update only your assigned row.
- **Follow-on plans:** Template 1 briefs feed Template 2; Template 3 triage feeds a
  `trace-replay-bug-fixing` fix session and a `docs/status/trace-frontier-log.md` update.
- **Doc obligations:** the Template 5 documentation table maps 1:1 to the commit trailers, so a
  passing review is the attestation that the trailer block can be filled with `updated`/`n/a`.
