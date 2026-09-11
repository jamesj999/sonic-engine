# Runbook: Fix a Trace Replay Divergence

For triaging and fixing a `*TraceReplay` test failure. The hard rule that overrides everything else: **trace data is comparison-only diagnostic input. Never hydrate/sync engine state from trace data in committed code.**

Companion skill: [`.agents/skills/trace-replay-bug-fixing/SKILL.md`](../../../.agents/skills/trace-replay-bug-fixing/SKILL.md) (+ S1: `s1-trace-replay`, `s1-retro-trace`). This runbook is the operational checklist.

---

## 0. Non-negotiables for trace work

1. **Comparison-only.** Do not call `player.setCentreX(trace.xPos())` or any setter sourced from trace snapshot/frame fields in committed code. Enforced by `TestTraceReplayInvariantGuard`.
2. **No carve-outs.** Fix the divergence by modeling the ROM state that drives the branch (object id/routine, status/control bits, frame-counter visibility, physics profile, event flag, data-driven condition). NEVER branch on zone id/name, trace route, frame number, or "known failing trace".
3. **Diagnostic hydration stays off.** `oggf.trace.hydrate` must remain unset (`TestTraceHydrateSwitchDefault`). Use it locally for diagnosis only; never commit it on.
4. **No game-name physics branches.** Use the smallest accurate owner from `docs/architecture/per-game-rule-placement.md`.

---

## 1. Discovery: read the trace artifacts

Trace report artifacts are produced under `target/trace-reports/`:

- `target/trace-reports/<game>_<zone>_report.json` — per-frame comparison, first divergence.
- `target/trace-reports/<game>_<zone>_context.txt` — surrounding context.
- `target/trace-reports/<game>_<zone>_physics_report.json` — physics-only frontier when `-Dtrace.verification=physics` is selected.
- `target/trace-reports/<game>_<zone>_animation_report.json` — animation-only frontier when `-Dtrace.verification=animation` is selected.
- Optional `src/test/resources/traces/<game>/.../aux_state.jsonl` — aux events/object state.

```powershell
# Run the failing replay (JUnit 5; quote selector). Example for an S2 zone:
mvn "-Dtest=*Mtz1TraceReplay" test "-Ds3k.rom.path=s3k.gen"

# Full sweep to pick the next target:
mvn "-Dtest=*TraceReplay" test

# Hold the existing movement frontier while animation is being fixed:
mvn "-Dtest=*TraceReplay" "-Dtrace.verification=physics" test

# Compare only Player/Sidekick animation ID and displayed mapping frame:
mvn "-Dtest=*TraceReplay" "-Dtrace.verification=animation" test
```

`trace.verification` defaults to `all`. Animation-only replay requires a CSV v7
trace, preventing an older fixture with no animation columns from passing
silently. Full reports retain a `verification_groups` summary for both gates.

Trace catalog default dir: `src/test/resources/traces` (`TRACE_CATALOG_DIR`). All replay tests extend `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` (or the credits-demo base).

---

## 2. Triage: identify the FIRST divergence before changing code

From the report, find the first divergent frame and field, then map to the likely owning subsystem:

| First divergent field | Likely owner | Files to inspect |
|-----------------------|--------------|------------------|
| Player position/speed/angle | Player physics | `com.openggf.sprites.playable.*`, `PhysicsProfile`, typed `GameRules` |
| Position only while on a platform/solid | Object solid contact | `ObjectManager.SolidContacts`, the riding object instance |
| Ring count / damage spike | Touch response | `ObjectManager.TouchResponses`, badnik instance |
| Camera | Event / camera | `Sonic{1,2,3k}LevelEventManager`, `Camera` |
| Sidekick fields | Sidekick rules | `SidekickCpuRules`, sidekick controller |
| Player/Sidekick animation ID or mapping frame | Animation selection/script/DPLC frame | `PlayableSpriteAnimation`, the game character animation provider |
| Palette / tile | Palette / layout | `PaletteOwnershipRegistry`, `ZoneLayoutMutationPipeline` |
| Diverges at frame 0 / before input | Test bootstrap | replay test setup |

Then search the disassembly for the relevant routine to confirm the ROM branch:

```powershell
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s2 search <Routine_Label>" -q
```

(S3K: `--game s2`→`--game s3k`, S&K-side labels only.)

---

## 3. Fix loop (diagnose → fix → regenerate → verify)

1. Model the ROM state at the divergence; change shared engine code to match (no carve-out).
2. Re-run the focused replay test.
3. If the trace itself needs regeneration (recorder extended), follow the regeneration workflow in the `trace-replay-bug-fixing` skill — do not hand-edit recorded data.
   S3K fixture regeneration records the authoritative CSV v7
   physics/animation stream and RAM-sampled aux/bootstrap events without the
   expensive PC-execution hooks by default. Install its physics, aux, and
   metadata outputs atomically.
   Set `OGGF_TRACE_ENABLE_DIAGNOSTIC_HOOKS=1` only when a focused frontier
   investigation needs the PC-execution diagnostics. The Linux launcher also
   defaults `OGGF_TRACE_QUIET=1` to avoid Mono's full Lua Console repaint on
   every status line; set it to `0` for interactive recorder output.
4. Re-run the full `*TraceReplay` sweep to confirm no regressions elsewhere.

---

## 4. Required guard tests + focused regression tests

```powershell
mvn "-Dtest=TestTraceReplayInvariantGuard" test     # comparison-only invariant
mvn "-Dtest=TestTraceHydrateSwitchDefault" test     # hydrate switch off
mvn "-Dtest=*TraceReplay" test                       # full sweep (regression check)
mvn "-Dtest=<Specific>TraceReplay" test              # your target
```

All tests JUnit 5 / Jupiter only.

---

## 5. Common failure signatures → fix

| Signature | Cause | Fix |
|-----------|-------|-----|
| `TestTraceReplayInvariantGuard`: "compare trace rows, not write them back" | Hydration setter from trace data | Remove it; let normal gameplay paths drive state. |
| `TestTraceReplayInvariantGuard`: parser depends on engine | Trace parser/data/catalog importing `GameServices`/`ObjectManager`/etc. | Keep parser/data engine-independent. |
| `TestTraceHydrateSwitchDefault` fails | `oggf.trace.hydrate=true` committed/CI-set | Unset it. |
| Replay fixed only for one zone, breaks others | Zone/route/frame carve-out | Re-model the actual ROM-state branch. |
| Replay test doesn't extend base | Missing `AbstractTraceReplayTest` | Extend the shared base class. |

---

## 6. ROM / disassembly citation expectations

Cite the exact routine label and the ROM-state condition (status bit, routine counter, event flag, profile field) that drives the branch you matched. Show the first divergent frame/field and the ROM vs engine values you reconciled.

---

## 7. Documentation & commit-trailer obligations

- **`docs/status/trace-frontier-log.md` MUST be updated** when a trace frontier moves, a trace fix is committed, a previously passing trace regresses, or a `*TraceReplay` sweep is used to choose the next target. Record: command, commit/worktree context, pass/fail status, error count, first-error frame/field.
- If the fix revealed a reusable pitfall: update `.agents/skills/s{1,2,3k}-implement-object/rom-pitfalls.md` and the mirrored `.claude/skills/...`, set `Skills: updated`.
- `CHANGELOG.md` + `Changelog: updated` for engine fixes.
- Fill all trailers; never `--no-verify`.
