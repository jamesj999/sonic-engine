---
name: s3k-animated-tiles
description: Use when implementing or correcting S3K AniPLC triggers, direct animated-art uploads, gating, or dynamic tile overrides.
---

# S3K animated tiles

Start at `Sonic3kPatternAnimator` and the ROM's per-act `Offs_AniFunc` dispatch.
An act can use a shared AniPLC list, custom direct DMA logic, or no animation.
Check both acts; a missing AniPLC list does not imply no animated art.

## Implementation

- Resolve the relevant `AnimateTiles_*` routine, script list, source art, VRAM
  destination, and gate inputs. Prefer `sonic3k.asm` addresses and verify bytes.
- `AniPlcParser` owns script decoding. Verify list count, duration mode, frame
  count, tiles per frame, source extent, and destination range before registration.
- Use the existing `AnimatedTileChannelGraph` / `S3kAnimatedTileChannels` path
  where applicable. Preserve channel identity, timers, reset behavior, and update order.
- Gates may consume camera/event state, level-start state, or a particular ROM
  clock. Read these through the existing runtime owner. A headless initialization
  gap should be handled at that boundary, not hidden by a blanket exception catch.
- Some `AnimateTiles_*` routines calculate art offsets and DMA directly without
  `AnimateTiles_DoAniPLC`; model that production path instead of inventing scripts.
- Dynamic overrides can replace art that a normal channel also targets. Preserve
  the ROM's order and ownership so the next animation tick does not erase an override.

For parser/data work, read [AniPLC format](references/aniplc-format.md).
For source lookup use `../s3k-disasm-guide/SKILL.md`; for competing runtime PLC
writes use `../s3k-plc-system/SKILL.md`.

## Verification

Exercise the changed channel's first update, timer wrap, gate transitions,
act/reset behavior, and target tile range as applicable. Check that source art
and scripts are ROM-backed and decoded extents are sane. When changing runtime
writes, verify renderer visibility/invalidation as well as CPU pattern changes.
A focused screenshot or capture helps check the animated region; tests should
assert source-derived behavior rather than duplicate the new switch statement.

Use an existing zone analysis's animated-tile section when available. Create or
expand analysis only when the requested behavior requires it. Read current
registrations rather than relying on an implementation-status catalog.
