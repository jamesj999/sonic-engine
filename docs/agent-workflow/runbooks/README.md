# Agent Runbooks

Executable, copy-paste runbooks for the recurring high-risk OpenGGF workflows. Each runbook is self-contained: an external agent with no chat context can pick one up, run the listed commands, touch the listed files, run the listed tests, and satisfy the documentation/commit obligations.

These runbooks implement **Option 1** of
[`docs/agent-workflow/support-options.md`](../support-options.md).

## Index

| Runbook | Use when |
|---------|----------|
| [`runbook-s3k-object.md`](runbook-s3k-object.md) | Implementing a Sonic 3&K object or badnik. **Primary / most detailed.** Start here for any S3K object work. |
| [`runbook-s1-s2-object.md`](runbook-s1-s2-object.md) | Implementing a Sonic 1 or Sonic 2 object or badnik. |
| [`runbook-s3k-zone-feature.md`](runbook-s3k-zone-feature.md) | Bringing up an S3K zone feature (events, parallax, animated tiles, palette cycling). |
| [`runbook-trace-divergence.md`](runbook-trace-divergence.md) | Triaging and fixing a `*TraceReplay` test divergence. |
| [`runbook-s1-v37-regen.md`](runbook-s1-v37-regen.md) | Regenerating S1 complete-run traces with recorder v3.7 (BizHawk) to unblock the gated red frontiers — `v_objstate`/`camera_boundary` + refreshed `object_near`/`slot_dump`. |
| [`runbook-multi-agent-trace-orchestration.md`](runbook-multi-agent-trace-orchestration.md) | Running a fleet of trace-replay bug-fixing agents as the lead/orchestrator (the continuous survey → assign → gate → merge → reassign loop). |
| [`runbook-rom-art-mappings-plc.md`](runbook-rom-art-mappings-plc.md) | Adding ROM-backed object art, sprite mappings, DPLCs, or PLC registration. |
| [`runbook-gameplay-level-mutation.md`](runbook-gameplay-level-mutation.md) | Editing level tile data from gameplay code (terrain modifiers, breakables, layout changes). |
| [`runbook-jvm-benchmark.md`](runbook-jvm-benchmark.md) | Measuring engine performance — comparing JVMs, checking a change for frame-time cost, or finding which subsystem dominates a frame. |

## Non-negotiable project rules (apply to every runbook)

These come from [`CLAUDE.md`](../../../CLAUDE.md) and [`AGENTS.md`](../../../AGENTS.md). Violating them fails code review or a guard test even when behavior looks correct.

1. **ROM-only runtime assets.** Object art, mappings, DPLCs, animation scripts, PLC data, and any other runtime asset bytes must come from the user-supplied ROM through the engine loader. Never read runtime asset bytes from `docs/` disassembly — `docs/` is for research, labels, and offset discovery only.
2. **S3K = prefer S&K-side addresses.** Use `sonic3k.asm` addresses (`< 0x200000`) in `Sonic3kConstants.java` and always run `RomOffsetFinder --game s3k`; pick the `sonic3k.asm` hit when both halves match. Rare exception: if an object has no S&K equivalent it may reference the `s3.asm` (S3-half) asset directly — use it after verifying, rather than looping on a non-existent S&K variant.
3. **No carve-outs.** Trace/physics fixes must model real ROM state (object id/routine/status bits, event flags, physics profile). Never branch on zone id/name, trace route, frame number, or "known failing trace".
4. **No game-name `if/else` for physics divergences.** Use the smallest accurate owner from `docs/architecture/per-game-rule-placement.md`.
5. **Trace data is comparison-only.** Never hydrate/sync engine state from trace data in committed code.
6. **Object code uses injected `ObjectServices`.** Call `services()`; never `getInstance()` in object code; never call `services()` in a constructor.
7. **ROM positions use center coordinates.** Use `getCentreX()`/`getCentreY()` / `NativePositionOps` for interactions, not top-left `getX()`/`getY()`.
8. **Tests are JUnit 5 / Jupiter only.** No `org.junit.*` (JUnit 4) imports, rules, or runners.
9. **Mirror skills.** If you create/modify a file under `.agents/skills/` or `.claude/skills/`, make the identical change in the mirrored tree.

## Commit-trailer obligations (non-merge, non-`master` commits)

Each non-merge commit on a non-`master` branch must carry these trailers, each starting with `updated` or `n/a` (the `prepare-commit-msg` hook auto-appends the block — fill it in). Merge commits skip trailer validation and are covered by the merge policy in `AGENTS.md` / `CLAUDE.md`:

`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`.

A `feat`/`fix`/`perf` commit touching `src/main/` must set `Changelog: updated` (and stage `CHANGELOG.md`) or justify with `Changelog: n/a: <reason>`. See [`runbook-...`] documentation sections and `.githooks/run-policy` for the authoritative trailer→file mapping. **Do not bypass with `--no-verify`.**
