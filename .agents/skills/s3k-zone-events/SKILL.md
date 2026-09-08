---
name: s3k-zone-events
description: Use when implementing or correcting S3K camera locks, arena flow, cutscenes, act transitions, terrain edits, or event-driven palette changes.
---

# S3K zone events

Find the selected act's `Dynamic_Resize`/`*_Resize` dispatch and current
`Sonic3k{Zone}Events` implementation. Include background/screen event routines
when they drive the requested behavior. Reuse an existing zone analysis where helpful.

## Porting checklist

- Map reachable routine stages, triggers, timer expiry, side effects, and next
  stage. Preserve routine stride and same-frame fallthrough/return semantics.
- Distinguish target/eased camera bounds from immediate bounds; use the event
  camera helpers. Sprite positions use ROM centers, while camera words are
  already world coordinates.
- Read camera, ROM, palette, audio, and runtime state through `Sonic3kZoneEvents`
  helpers. They are rebound during `init()`; do not cache camera state in a constructor.
- Check act and Sonic/Tails/Knuckles branches, boss/child allocation behavior,
  water/chase state, and event flags used by scroll or animated-art owners.
- Keep shared event state in a typed `ZoneRuntimeRegistry` adapter where applicable.
  Shared consumers use semantic state rather than game/zone exceptions.
- Route layout changes through `ZoneLayoutMutationPipeline` / mutation surfaces.
  Request act transitions through existing APIs; `LevelActTransitionExecutor`
  owns in-place reload execution, not the event handler.
- Preserve palette write ordering through the event helpers and
  `PaletteOwnershipRegistry`. Timer-driven AnPal cycles have a separate owner.
- Submit PLC work through the existing event/art path and preserve readiness
  gates. A preloaded sheet is not proof the ROM event should advance now.

## Scope and integration

Change the existing handler when it already owns the behavior. An `rts` dispatch
entry does not by itself require a new no-op handler. Derive zone support and
registration from current code, not a dated zone feature table.

Check snapshot/reset behavior for any persistent stage, timer, or shared event
field added by the change. Coordinate a new shared state contract before changing
multiple event/scroll/art consumers.

## Verification and references

Exercise the changed threshold from both sides, state transition timing, and
camera/control release. For a route blocker, verify the route can proceed through
the event; level-load success alone is insufficient.

- `../s3k-disasm-guide/SKILL.md`: source/offset lookup.
- `../s3k-plc-system/SKILL.md`: event-triggered art and refresh.
- `../s3k-palette-cycling/SKILL.md`: AnPal versus mutation ownership.
- `../trace-replay-bug-fixing/SKILL.md`: replay divergence.
- `docs/agent-workflow/runbooks/runbook-s3k-zone-feature.md`: additional tooling
  when a multi-part zone task needs it.
