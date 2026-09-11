---
name: s3k-palette-cycling
description: Use when implementing or correcting S3K AnPal palette cycles, timer/counter semantics, or palette write ownership.
---

# S3K palette cycling

Find the active `AnPal_*` through the ROM's `AnimatePalettes` dispatch and
`Sonic3kPaletteCycler`. Read the requested act/character path; some entries are
no-ops or state-bit effects rather than table-driven color cycles.

## Ownership

Timer-driven `AnPal` behavior belongs in the cycler. Camera/event-triggered
palette mutations generally belong in the zone event handler. Existing coupled
cases need source-aware treatment: do not move behavior merely to satisfy this
classification. Both write through `PaletteOwnershipRegistry` and the existing
`S3kPaletteWriteSupport`/event helpers so concurrent writes retain their ordering.

## Porting checklist

- Identify each timer and counter, their storage width, decrement/expiry order,
  increment step, wrap/mask, table address, and destination colors.
- Counters may be byte offsets into color tables rather than frame indices;
  shared timers and masked counters are not interchangeable with independent cycles.
- Preserve read-before-increment versus increment-before-read. Verify first-frame
  initialization and behavior when the camera/event gate opens or closes.
- Resolve tables through `Sonic3kConstants` and the ROM pipeline; verify table
  length and writes. Do not hardcode palette asset bytes from the disassembly.
- Translate a `Normal_palette_line_N+$XX` destination to zero-based line `N-1`
  and color index `XX/2`; check cross-line writes and packed color conversion.
- Read the current animation tick owner when writing headless tests. Tests that
  bypass that owner must advance the same production update path to observe cycles.

AIZ offers both cycle and mutation examples: waterfall/torch `AnPal` channels
cycle colors; `AIZ1_Resize` changes hollow-tree color at a camera threshold.
Inspect those source paths for ordering, not as universal zone constants.

## Verification and references

Compare source-derived initial values, expiry edge, wrap/mask edge, shared-timer
updates, and destination color ranges for affected channels. Verify that event
writes and cycling coexist when they share a palette line. Visual comparison
should cover the changed effect and the relevant gate transition.

Use `../s3k-disasm-guide/SKILL.md` for offsets and
`../s3k-zone-events/SKILL.md` for event-driven writes. If a zone analysis exists,
read its palette section as a starting point and verify against current code/ROM.
Implementation status comes from the live cycler and evidence, not a zone inventory.
