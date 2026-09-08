# Sonic 3 & Knuckles task reference

Use this when a task reaches S3K-specific behavior. Repository-wide rules and
delivery priorities live in `AGENTS.md`; current gaps and evidence live in
`docs/S3K_KNOWN_DISCREPANCIES.md` and `docs/status/trace-frontier-log.md`.

## Source decisions that are easy to confuse

- Prefer locked-on `sonic3k.asm` addresses in the S&K half (`< 0x200000`).
  Use `RomOffsetFinder --game s3k` and verify the routine's pointer; some objects
  legitimately reference the S3 half. A matching duplicate is not enough.
- S3KL object IDs apply to zones 0–6, including FBZ=4; SKL applies to 7–13.
  Resolve names through `Sonic3kObjectRegistry.getPrimaryName(id, zoneSet)`.
  Object-table choice and level-half predicates are separate decisions.
- `AnPal_*` cycling and event-driven palette mutations are separate owners.
  Writes compose through the palette ownership path; preserve ordering where
  a zone couples them. Headless tests must tick the production animation owner.
- Animated art can use AniPLC scripts or custom direct uploads. Inspect both
  act dispatch entries before concluding that an absent script means no animation.
- Direct Kosinski jobs and KosM module parents have distinct queue identity.
  Read the timing contract before changing recorded readiness/lag admission;
  contract scope does not imply producer or fixture coverage.

## Task routing

Read the relevant entrypoint, then only references needed for the task:

| Task | Entrypoint |
|---|---|
| Disassembly / ROM offsets | `.agents/skills/s3k-disasm-guide/SKILL.md` |
| Object / badnik | `.agents/skills/s3k-implement-object/SKILL.md` |
| Boss | `.agents/skills/s3k-implement-boss/SKILL.md` |
| Events / transitions / terrain | `.agents/skills/s3k-zone-events/SKILL.md` |
| Parallax / water split | `.agents/skills/s3k-parallax/SKILL.md` |
| Animated art | `.agents/skills/s3k-animated-tiles/SKILL.md` |
| Palette cycling | `.agents/skills/s3k-palette-cycling/SKILL.md` |
| PLC art / refresh | `.agents/skills/s3k-plc-system/SKILL.md` |
| Substantial route bring-up | `.agents/skills/s3k-zone-bring-up/SKILL.md` |
| Replay failure | `.agents/skills/trace-replay-bug-fixing/SKILL.md` |

Use `docs/architecture/object-implementation-reference.md` for object utility
and behavior contracts, `docs/architecture/engine-map.md` for runtime ownership,
and `docs/agent-workflow/README.md` for CLI tooling when needed. Search object
pitfall catalogs by symptom rather than loading them in full.
