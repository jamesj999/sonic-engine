---
name: s3k-zone-bring-up
description: Use for a substantial S3K zone or playable-route bring-up spanning events, objects, scroll, palette, and art; use the individual skill for a local fix.
---

# S3K zone bring-up

Advance the requested playable route using the zone's current implementation and
ROM evidence. Treat a zone abbreviation as a scope to investigate, not a command
to reimplement every subsystem. Validation-only requests inspect/test existing behavior.

## Establish the slice

Read the existing analysis under `docs/architecture/research/s3k-zones/` and the
relevant live event/object/scroll/art registrations. Use `../s3k-zone-analysis/SKILL.md`
when substantial source analysis is missing. Identify traversal blockers, event
flow, necessary visuals/art readiness, sidekick interactions, and transition exit.

Choose only applicable missing behavior. An `rts` handler needs no implementation
unless another required effect is absent. Shared routines may still require
zone-specific data/registration; existing classes may still have route gaps.

## Delivery

- Resolve shared state and update boundaries before implementing consumers:
  event state, camera/water, palette ownership, animation channels, terrain
  mutation, and render modes should use their existing runtime owners.
- Implement the requested route blockers first. Avoid broad migrations unless
  they remove a concrete obstacle or duplication in the work being changed.
- Independent feature work may run in separate worktrees when useful; give each
  task a concrete owner, behavior, source evidence, dependencies, and validation
  target. Keep dependent state-contract changes sequential.
- Integrate using the repository workflow. Inspect shared-file conflicts
  semantically; additive-looking constants or registrations can still disagree
  about addresses, ordering, or ownership.
- Continue authorized implementation without a routine human review gate. Ask
  only when required information or an unresolved consequential choice blocks progress.

## Conditional skills

| Work | Skill |
|---|---|
| Camera, arena, cutscene, transition, terrain | `../s3k-zone-events/SKILL.md` |
| Scroll/deformation, water/render split | `../s3k-parallax/SKILL.md` |
| AniPLC/direct animated upload | `../s3k-animated-tiles/SKILL.md` |
| AnPal cycling | `../s3k-palette-cycling/SKILL.md` |
| Traversal object/badnik | `../s3k-implement-object/SKILL.md` |
| Boss encounter | `../s3k-implement-boss/SKILL.md` |
| PLC/queue/art refresh | `../s3k-plc-system/SKILL.md` |

## Validation

Verify the route's entry, blocker passage, event/camera progression, and exit.
Exercise changed subsystem boundaries with focused tests and ROM-backed evidence;
use representative visual capture for rendering effects. Keep the project's
S3K bootstrap/loading gates and required full-suite comparison intact.
Report the actual route advanced, validation gaps, and unresolved blockers.
A level loading or a collection of merged classes is insufficient evidence of parity.
