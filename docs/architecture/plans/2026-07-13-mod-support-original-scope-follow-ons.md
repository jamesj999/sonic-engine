# Mod Support Original-Scope Follow-ons

**Status:** Historical implementation plan, with release disposition refreshed
2026-09-08. Workstreams A (base-game SFX overrides) and B (S1 adapter) remain
scheduled under the [0.8 roadmap](../../project/v0.8-roadmap.md). Workstream C's
bounded S3K format-v2 adapter is delivered; see the
[current contract](../../modding/formats/level-definition.md). Its broader runtime
expansion is not implied by this delivered status. The old branch basis below
records the original audit, not current implementation.

**Branch/status basis:** `next` at `5e0c714e8`. This schedule does not assume that
anything exists on `develop`, and each workstream must rebase its design evidence on
the then-current `next` before implementation.

**Source commitments:** the [root design](../designs/2026-07-09-mod-support-design.md)
§1 and §8, [Phase 1 Non-goals](../designs/2026-07-09-mod-support-phase1-design.md#non-goals),
and the [Phase 2 S2-flagship narrowing](../designs/2026-07-09-mod-support-phase2-design.md#goal).

This plan owns the three original-scope commitments that Phase 4 may not silently
park or drop. Each workstream gets its own implementation design and delivery branch
before code starts. Ordering is intentional: the audio work reuses a landed standalone
one-shot path; the zone adapters are isolated by game because their load models differ.

## Workstream A — base-game streamed SFX overrides

**Owner:** audio/mod-runtime team.

**Schedule:** first mod-support follow-on after Phase 4.

**Delivery boundary:** a dedicated approved design, test-first implementation plan,
and delivery branch; it does not share an implementation branch with either zone
adapter.

- Add a separately typed manifest mapping from a base-game SFX identity to an owned
  `SfxKey`; do not overload numeric music `audioOverrides`.
- Define S1/S2/S3K SFX identity domains and later-wins conflict findings.
- Reuse the bounded decode/cache/16-voice one-shot path landed for standalone games,
  while preserving stock SMPS behavior when no override resolves.
- Preserve rewind policy: streamed one-shots are suppressed during rewind and trace
  data is never used to hydrate engine state.
- Require manifest golden tests, hostile-input validation, per-game routing isolation,
  stock parity, audio cache/voice limits, and the default suite/S3K/trace gates.

**Prerequisite:** decide the manifest field name and the stable S1/S2/S3K stock-SFX
key vocabularies; this is a format addition and must receive compatibility/security
review before implementation.

**Acceptance:** one maintained patch fixture per stock game overrides a named stock
SFX with a bounded WAV/OGG asset; later-owner collision reporting is deterministic;
disabling or omitting the override preserves the original SMPS route bit-for-bit; bad
identity, asset, decode, gain, duration, cache, and voice-limit cases fail with exact
findings; rewind suppression and the stock trace spots remain green.

## Workstream B — Sonic 1 mod-zone adapter

**Owner:** Sonic 1 level-loading/mod-zone team.

**Schedule:** after Workstream A, unless a real Sonic 1 zone adopter raises priority.

**Delivery boundary:** its own approved S1 design, test-first plan, and delivery
branch; it must not be hidden inside a generic cross-game adapter change.

- Introduce the smallest provider/load-source seam that lets a `ModLevelDefinition`
  supply an S1 zone without converting all stock S1 loading to plans.
- Reuse `ZoneKey`, `ZoneProgressionPlan`, namespaced objects, save fallback, title-card
  fallback, and owner fault boundaries from the S2 implementation.
- Keep stock S1 byte-identical and avoid raw game/zone/frame branches in shared runtime.

**Prerequisite:** document the S1-specific level-loading boundary that Phase 0
identified as bypassing `LevelResourcePlan`, including which stock resources remain
ROM-owned and which `ModLevelDefinition` fields become source-owned.

**Acceptance:** a maintained minimal original-data S1 patch zone loads headless,
progresses through a valid results boundary, saves/resumes by tagged identity, falls
back safely when disabled, rejects unsupported/eventful requirements before
publication, and leaves S1 plus cross-game trace spots unchanged on stock routes.

## Workstream C — Sonic 3&K mod-zone adapter

**Owner:** Sonic 3&K level-loading/runtime-framework team.

**Schedule:** after the S1 adapter, or earlier only when an S3K creator supplies a
concrete zone fixture.

**Delivery boundary:** its own approved S3K design, test-first plan, and delivery
branch; it cannot be implemented as an unreviewed extension of the S2/S1 work.

- Adapt `ModLevelDefinition` to the S3K zone-set/load topology without bypassing the
  runtime-owned zone, palette, animation, mutation, scroll, and render registries.
- Reuse tagged progression/save identity and preserve S3K locked-on ROM addressing
  rules for stock assets; mod assets remain owner-bounded.
- Define what a minimal no-event S3K mod zone initializes, and reject unsupported
  requirements before session publication.

**Prerequisite:** catalogue the S3K zone-set, locked-on addressing, PLC, palette,
animation, mutation, scroll, render, event, and rewind-registry obligations; do not
treat the S2 in-memory constructor as a drop-in adapter.

**Acceptance:** a maintained minimal original-data S3K mod zone loads headless with a
declared zone-set identity, initializes every required runtime registry (or an explicit
no-op contract), saves/resumes and disables safely, rejects unsupported requirements
before publication, and passes the S3K must-keep-green set plus stock-route trace
spots.

## Exit criteria

Each workstream needs its own approved design, test-first implementation plan,
independent spec and quality reviews, changelog/creator-guide updates, and verified
stock parity. Scheduling here is not authorization to absorb any workstream into
Phase 4.
