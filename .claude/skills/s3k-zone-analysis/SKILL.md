---
name: s3k-zone-analysis
description: Use when a new or substantially incomplete S3K zone needs a disassembly-backed feature catalogue; reuse existing analysis for local fixes.
---

# S3K zone analysis

Produce the source-backed information needed to scope a playable route or
substantial zone feature. Check `docs/architecture/research/s3k-zones/` for an
existing analysis before creating another. A narrow fix can inspect its owner directly.

## Research scope

Start from the selected zone, act, character, and requested route. Inspect live
registrations to distinguish implemented behavior from actual gaps.

| Concern | ROM entrypoints / evidence |
|---|---|
| Events and transitions | `Dynamic_Resize`, `*_Resize`, background/screen event routines |
| Scroll and rendering | `*_Deform`, init routines, height/index tables, water/shake inputs |
| Animated tiles | `Offs_AniFunc`, `AnimateTiles_*`, AniPLC lists, custom direct uploads |
| Palette | `AnimatePalettes`/`AnPal_*`; event-driven mutations separately |
| Objects and bosses | Zone-set pointer table, object placements, subtype and character paths |
| Art readiness | PLC calls, direct Kosinski/KosM submissions, event gates |

Use `rg` against `docs/skdisasm/sonic3k.asm`; follow actual pointers into `s3.asm`
when required. `../s3k-disasm-guide/SKILL.md` covers half/table selection and
verified offsets. A source filename and an object-table zone set answer different questions.

## Output

Save durable analysis to
`docs/architecture/research/s3k-zones/{zone}-analysis.md`.
Record the source revision, relevant labels, ROM address verification, and
which acts/characters were inspected. Mark unexamined behavior explicitly.

For each relevant subsystem record:

- Owning ROM routine and engine owner, existing coverage, and specific missing behavior.
- Inputs/triggers, state/timer semantics, output effects, source data, and boundaries.
- Dependencies on another subsystem (for example event water height consumed by scroll).
- A concrete validation target and remaining uncertainty.

Keep detailed routine/asset tables only where they support implementation decisions.
Separate AnPal cycling from event mutations and identify direct animated uploads
that do not use AniPLC scripts. Do not infer a feature exists from a name alone.

If consumers need the established 13-section format, normalize it with:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.ZoneSpecNormalizerTool" \
  "-Dexec.args=docs/architecture/research/s3k-zones/<zone>-analysis.md"
```

Recommend the next route-blocking work from the evidence. Leave implementation
and orchestration to the requested delivery task; analysis alone does not authorize
unrelated zone implementation.
