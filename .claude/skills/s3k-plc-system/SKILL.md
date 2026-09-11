---
name: s3k-plc-system
description: Use when changing S3K PLC loading, art registration, runtime tile replacement, or renderer refresh.
---

# S3K PLC and art loading

Use `Sonic3kPlcLoader` for level PLC operations and `Sonic3kPlcArtRegistry` for
object art registration. Resolve the ROM's actual PLC and art references before
selecting an engine path. `../plc-system/SKILL.md` covers the shared binary format.

## Ownership choices

- `StandaloneArtEntry`: dedicated object/boss sheets with independent patterns.
- `LevelArtEntry`: sheets backed by level tiles and subject to runtime replacement.
- `Load_PLC` appends queue entries; `Load_PLC_2` clears then loads. Preserve that
  distinction at the owning submission path.
- `preDecompress()` prepares art for later application; it does not by itself
  authorize the runtime transition or make every job ready.

Prefer `sonic3k.asm` offsets; verify a genuine S3-half pointer when required.
The `RomArtIntakeTool` can resolve several labels together:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.RomArtIntakeTool" \
  "-Dexec.args=ArtNem_AIZSwingVine Map_AIZSwingVine"
```

Treat source-half warnings as verification prompts, not a ban on valid S3 assets.
For detailed intake use `docs/agent-workflow/runbooks/runbook-rom-art-mappings-plc.md`.

## Runtime refresh

Zone events use their `applyPlc()` helper for the existing event-owned path.
For lower-level changes, inspect `applyToLevel()` and `refreshAffectedRenderers()`:
modified tile ranges must reach overlapping sheets registered through
`Sonic3kObjectArtProvider.registerLevelArtSheet`. Verify GPU updates as well as
CPU pattern data. A standalone sheet does not require level-range refresh.

## Verification

When changing PLC-backed/standalone art, mapping addresses, decompression sources,
or registry entries, run the mapping corruption guard with the discovered absolute ROM path:

```bash
mvn "-Ds3k.rom.path=$S3K_ROM_PATH" \
  "-Dtest=TestSonic3kPlcArtRegistry#s3kArtRegistryMappingsStayWithinSaneSpriteSheetLimits" test
```

For known assets, verify source-backed frame/piece counts, tile dimensions, and
indices. Keep `TestPatternSpriteRendererCorruptionGuard` passing when changing
renderer or sheet construction; use relevant queue tests for scheduling changes.

## Timing evidence

Direct Kosinski jobs and KosM parents are distinct physical domains; a module's
direct child is not another module parent. For `queue.*` reports or readiness
admission, read the current hardware timing contract linked from
`../plc-system/SKILL.md` and use `../trace-replay-bug-fixing/SKILL.md`.
Do not infer current recorder coverage from contract scope.
