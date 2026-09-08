---
name: plc-system
description: Use when changing cross-game PLC parsing, ROM-backed art loading, or decompression queue behavior.
---

# Pattern Load Cues

`com.openggf.level.resources.PlcParser` owns the shared Nemesis PLC format.
Read the caller to distinguish level-buffer uploads, standalone object art,
and deferred queue work; these have different ownership and readiness effects.

## Binary format

- Offset table: word offsets relative to the table start, indexed by PLC ID.
- Definition: signed word count minus one; negative means empty.
- Each entry: long ROM art address followed by word VRAM byte destination.
  The tile index is destination / 32.

Resolve table addresses through the game's constants and verify the actual ROM.
Do not infer PLC IDs or compression from a nearby zone's data.

## Engine ownership

- `PlcParser.parse()` returns a definition; `toPatternOps()` feeds a level plan.
- `decompressEntry()` / `decompressAll()` return independent pattern arrays for
  standalone sheets. `decompressEntryRaw()` supplies bytes for level-buffer work.
- Level-buffer art intentionally overwrites tile ranges. Standalone sheets avoid
  sharing those ranges, but do not replace production queue timing/readiness.
- S3K loading/refresh is in `Sonic3kPlcLoader` and `Sonic3kPlcArtRegistry`;
  use `../s3k-plc-system/SKILL.md` for those operations.

For ROM art/mapping intake, consult
`docs/agent-workflow/runbooks/runbook-rom-art-mappings-plc.md` when needed.

## Queue timing

Separate submission, execution/service, completion, and render visibility.
For a lag row, identify which ROM loop it represents and whether queue service
ran. A held `RunPLC` iteration is not a new submission just because another
comparison row exists. Preserve queue clearing versus appending and the ROM's
actual service clock rather than fitting elapsed frames to one movie.

Trace data is comparison-only except for the dedicated timing input contract:
`docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`.
Read it before changing timing admission. It owns readiness-release and lag-loop
admission semantics, current schema, implementation scope, and fixture coverage.
Keep those distinctions out of hardcoded per-game/per-movie assumptions here.

For `queue.*` / `dynamic_art.*` divergences, use
`../trace-replay-bug-fixing/SKILL.md`. Preserve first frame, field, and error count
in the trace frontier log when the project's evidence rules require it.

## Verification

Exercise the affected parser boundary, queue transition, or tile range. For art,
verify decoded dimensions/mappings as well as successful decompression. For timing,
check the production job/loop and relevant replay evidence; a visually correct
sheet does not prove scheduling parity.
