---
name: s3k-parallax
description: Use when implementing or correcting S3K scroll/deformation behavior, including band fills, water splits, and runtime background changes.
---

# S3K parallax and deformation

Find the active handler through `Sonic3kScrollHandlerProvider`, then the ROM
`*_Deform`, background init/event, and deformation tables. Reuse an existing zone
analysis if relevant; a local scroll fix does not need a new full-zone analysis.

## Porting decisions

- Distinguish the intermediate `HScroll_table` from the final scanline buffer.
  `ApplyDeformation` distributes intermediate words according to band heights;
  height bit 15 selects per-line word consumption.
- Preserve arithmetic width, sign extension, fractional accumulation, shift order,
  and scanline coverage. Use `M68KMath` for instruction-width operations and
  existing `ScrollEffectComposer` / deformation plan helpers where they match.
- Identify which camera snapshot, event state, water height, shake offset, and
  frame clock the routine consumes. Keep shared state in the existing typed
  runtime owner rather than duplicating event flags inside the scroll handler.
- Load deformation tables and runtime art through ROM-backed resources. A
  disassembly table is research evidence, not a runtime fallback or Java asset array.
- Check initialization and mode transitions, including boss modes, vertical wrap,
  and acts that share routines but differ in inputs or tables.

For scatter-fill and arithmetic details, read
[deformation mechanics](references/deformation.md) only as needed.

## Diagnose the rendering boundary

A seam or static/garbage row may involve scroll math, tile upload, or renderer
composition. Compare the relevant boundary before expanding the fix:

- Water split: world water Y versus camera-relative scanline, underwater palette,
  ripple indexing, surface sprites, and clipping at the top/bottom of the screen.
- Background art: level pattern coverage, AniPLC/direct upload, startup repair
  strips, and invalidation of sheets after runtime tile replacement.
- Shake: whether the ROM removes shake before computing a ratio and restores it
  afterward, versus applying a scaled shake to the whole background.

Use `../s3k-animated-tiles/SKILL.md` or `../s3k-plc-system/SKILL.md` when evidence
points to upload ownership; use `../s3k-zone-events/SKILL.md` for event state.

## Verification

Compare the changed bands or scanlines against the ROM calculation at boundaries
and representative camera values, including negative/wrapped positions where used.
Check handler registration when adding a handler. For visible effects, capture
representative engine output and compare the affected region with ROM evidence.
Record unavailable visual evidence as a gap; do not call a compile-only result
visual parity. Integration/full-suite requirements come from the repository.
