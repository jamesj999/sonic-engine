---
name: s2-implement-object
description: Use when implementing or changing a Sonic 2 object or badnik from the disassembly. For a boss, use s2-implement-boss.
---

# Sonic 2 objects and badniks

Locate the existing implementation, registry entry, and ROM routine before
adding a class. The object ID, zone/act, subtype, and character path determine
which behavior is required; derive unspecified details from the requested route.

## Porting checklist

- Read the routine dispatch, init fallthrough/return, movement, collision,
  animation, sound, child spawning, and deletion paths that the object can take.
  Preserve 68000 widths, signed comparisons, update order, and timer expiry edges.
- Use ROM center coordinates and `NativePositionOps` for playable-sprite writes.
  Radius/status changes do not imply a ROM position change. Distinguish the
  object-visible V-int clock from executed frames and the level clock.
- Reuse the closest existing behavior contract. The utility/API reference is
  `docs/architecture/object-implementation-reference.md`; consult the relevant
  part for movement, touch, solids, control, participation, or lifetime behavior.
- Keep native player-slot semantics explicit. Check per-player state, child-to-parent
  writes, allocation failure, and same-pass update visibility where the ROM uses them.
- Load art, mappings, DPLCs, and animation data through the ROM pipeline. Verify
  addresses and data shape, then register art and the object factory.
- Capture new persistent state and recreate paths for rewind. Exercise the
  affected routine edge/subtype with focused tests and the relevant object/art guards.
  Use trace replay when it provides evidence for the changed behavior.

## Game-specific entrypoints

S2 object routines are mostly inline in `docs/s2disasm/s2.asm`; art and
mappings are separate resources. Distinguish per-player SST state from
object-wide state, and preserve slot ordering for parent/child writes.

Start code searches in `src/main/java/com/openggf/game/sonic2/`.
Use `Sonic2ObjectRegistry` for registration and the game's art provider for rendering.
Check the existing object checklist/profile when implementation coverage changes.

## Conditional references

- For label/address lookup, use `../s2disasm-guide/SKILL.md`.
- Search [ROM pitfalls](rom-pitfalls.md) by symptom or routine before reading matching
  entries: `rg -n '^##|standing|timer|child|touch|slot' <skill-dir>/rom-pitfalls.md`.
  These are source-cited examples, not a requirement to read the full catalog or
  apply an S2 convention to another game without checking its routine.
- For art queues or PLC parsing, use `../plc-system/SKILL.md`;
  for a failing replay, use `../trace-replay-bug-fixing/SKILL.md`.

Read only references needed for the behavior being changed. Integration and
project-wide verification follow the repository instructions.
