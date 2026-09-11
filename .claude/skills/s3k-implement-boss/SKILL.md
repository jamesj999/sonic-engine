---
name: s3k-implement-boss
description: Use when implementing or changing a Sonic 3 & Knuckles boss, including arena events, hit handling, children, defeat, and escape.
---

# Sonic 3 & Knuckles bosses

Start with the existing boss family and zone event owner. Scope the encounter
from arena entry through defeat, escape, and the next playable transition.

## ROM behaviors to resolve

- Arena thresholds, camera bounds/locks, entry timer, music, art readiness,
  boss allocation, and failure to allocate required children.
- Routine dispatch and init timing; movement arithmetic; hit count, damage
  eligibility, collision disable/restore, flashing, and attack cooldowns.
- Parent/child slot order, immediate shared-state writes, and which components
  remain collidable or persistent after the main body changes routine.
- Killing-hit timing versus the first defeat update, explosion allocation and
  identity, escape movement, camera release, capsule/signpost, and act handoff.

Preserve the ROM's order and owning state for each branch. Use an existing boss
base only where its contract matches; keep encounter-specific phases explicit.

## Integration

Resolve zone-set-specific IDs before porting the boss. Check both acts and
Sonic/Tails versus Knuckles branches: miniboss transitions and end-boss arenas
may share art without sharing event flow. Arena state belongs in the zone event
owner; shared consumers read semantic runtime state.

Objects use injected `services()`. Native player position writes use
`NativePositionOps`; lifecycle, player control, participation, and rewind use
the existing object contracts described in
`docs/architecture/object-implementation-reference.md`.

Load boss art through the game's ROM-backed provider/PLC path and verify mappings
against the chosen art. Register the factory and any persistent child recreate paths.
Do not treat an offscreen escape as ordinary object culling without checking the ROM.

## Verification and references

Exercise the changed encounter boundaries: arena entry, killing hit, first defeat
update, child cleanup, and camera/transition release as applicable. Run relevant
art and rewind guards when those contracts change. A source-backed replay can
verify frame ordering; a screenshot alone cannot establish it.

- Use `../s3k-disasm-guide/SKILL.md` for locating routines and assets.
- Use `../s3k-implement-object/SKILL.md` for ordinary object/art integration;
  search its `rom-pitfalls.md` for the affected interaction rather than reading all entries.
- Use `../plc-system/SKILL.md` for queue/PLC ownership and
  `../trace-replay-bug-fixing/SKILL.md` for replay divergence.

Project workflow owns integration and full-suite requirements.
