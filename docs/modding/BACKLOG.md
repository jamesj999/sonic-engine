# Mod-support deferred backlog

This is the Phase 4 disposition of deferred mod-support work. It is a triage record,
not an implementation promise. Its linked dated design/plan corpus is historical
scope provenance, not current API authority. The sole current version and publication
authority is the root `mod-api-release-policy.properties` descriptor; the
[Mod API compatibility guide](../architecture/mod-api-compatibility.md) explains it.
The sweep covered the root mod-support design, every
Phase 0–4 design and implementation plan, and the shared format/security contract,
case-insensitively for `defer`, `out-of-scope`, `parked`, `follow-on`, `future`,
`revisit`, `when-demanded`, `later`, `optional`, `narrowing`, `non-goal`,
`unsupported`, and `TODO`.

## Current release disposition (2026-09-08)

The [0.7 roadmap](../project/v0.7-roadmap.md) closes stock campaigns while
retaining feature regression coverage. The [0.8 roadmap](../project/v0.8-roadmap.md)
then owns feature qualification and API publication. The audit below is historical
unless a row records a later disposition; current implementation takes precedence.

S3K's bounded format-v2 host adapter has since landed (including object-set and
palette ownership, tagged saves, and a deliberately empty/flat-scroll runtime).
That delivers the bounded adapter commitment, not arbitrary stock zone events or
editor live re-apply. Base-game streamed SFX overrides and the S1 zone adapter
remain scheduled 0.8 follow-ons. Their public additions must be reviewed before
API publication; the roadmap alone changes no API version or compatibility rule.

## Audit basis and sweep inventory

This review was run on branch **`next` at `5e0c714e8`** after the Phase 4 sample
gallery landed. It did not compare against, or infer status from, `develop`. Landed
status was checked in that historical `next` tree: dependency resource lookup
returned no resources, additive `ModZoneLoader` was Sonic-2-specific, the manifest
contained music-only `audioOverrides`, standalone manifests could not register
patches, and S3K editor runtime re-apply was gated. Later dispositions are recorded
above and in the affected rows.

The exact corpus was the [root design](../architecture/designs/2026-07-09-mod-support-design.md),
all five sibling designs ([Phase 0](../architecture/designs/2026-07-09-mod-support-phase0-design.md),
[Phase 1](../architecture/designs/2026-07-09-mod-support-phase1-design.md),
[Phase 2](../architecture/designs/2026-07-09-mod-support-phase2-design.md),
[Phase 3](../architecture/designs/2026-07-09-mod-support-phase3-design.md), and
[Phase 4](../architecture/designs/2026-07-09-mod-support-phase4-design.md)), their five
[implementation plans](../architecture/plans/2026-07-09-mod-support-phase0.md)
([Phase 1](../architecture/plans/2026-07-09-mod-support-phase1.md),
[Phase 2](../architecture/plans/2026-07-09-mod-support-phase2.md),
[Phase 3](../architecture/plans/2026-07-09-mod-support-phase3.md), and
[Phase 4](../architecture/plans/2026-07-09-mod-support-phase4.md)), and the
[shared format/security contract](../architecture/designs/2026-07-10-mod-support-format-security-contracts.md).
The case-insensitive sweep treated hyphen/space spellings as the same marker (for
example, `out-of-scope`/`out of scope` and `when-demanded`/`when demanded`) and
treated `defer` as the mandated stem, so it also counts `deferred`/`deferral`. It
produced:

| Marker | Occurrences |
|---|---:|
| `defer` | 30 |
| `out-of-scope` | 9 |
| `parked` | 5 |
| `follow-on` | 9 |
| `future` | 15 |
| `revisit` | 5 |
| `when-demanded` | 3 |
| `later` | 30 |
| `optional` | 46 |
| `narrowing` | 4 |
| `non-goal` | 10 |
| `unsupported` | 11 |
| `TODO` | 2 |
| **Total** | **179** |

Those occurrences occupy **155 matching lines across all 12 files**. The two `TODO`
hits are the Phase 4 sweep vocabulary itself, not unfinished work. Repeated phase/plan
references collapse into the independently judgeable rows below. Each row names the
exact design section, owner, demand evidence, cost/risk, and verdict; related source
bullets are split when their ownership or implementation cost can diverge.

Verdicts mean:

- **Schedule** — a separately owned plan exists; the item is not implemented here.
- **Keep parked** — preserve the seam or documented limitation, but wait for concrete
  demand or evidence.
- **Drop** — remove from the mod roadmap; a future proposal needs fresh justification.
- **Delivered** — implemented and merged after this triage; the row is kept (not
  removed) as the audit trail from finding to fix, with pointers to the tests that
  cover it.

No external adopter reports were present in the repository at review time. Demand
evidence therefore comes from the five maintained samples and from explicit original
spec commitments.

## Scheduled original-scope commitments

| Item | Original source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| Base-game streamed SFX overrides | Root design §1 manifest override maps and §4 audio; Phase 1 non-goals; shared contract §1 audio manifest | Audio/mod runtime | The standalone sample proves bounded WAV one-shot decode/mix, reducing implementation risk, but base-game identity, manifest vocabulary, conflict reporting, and SMPS fallback remain unsolved. | **Schedule**. [Workstream A](../architecture/plans/2026-07-13-mod-support-original-scope-follow-ons.md#workstream-a--base-game-streamed-sfx-overrides). |
| Sonic 1 new-zone adapter | Root design §8 Phase 2; Phase 0 §B; Phase 2 goal/out-of-scope | S1 level loading/mod zones | Original scope explicitly retained it. S1 bypasses the S2 plan seam, so a safe adapter needs dedicated architecture and parity gates. | **Schedule**. [Workstream B](../architecture/plans/2026-07-13-mod-support-original-scope-follow-ons.md#workstream-b--sonic-1-mod-zone-adapter). |
| Sonic 3&K new-zone adapter | Root design §8 Phase 2; Phase 2 goal/§D | S3K level/runtime frameworks | Bounded format-v2 adapter landed after the audit: selected object set, host palette ownership, tagged save identity and flat-scroll/no-stock-events runtime. | **Delivered** in the inspected `next` tree (`ed652eefa`); see the [current contract](formats/level-definition.md) and `Sonic3kModZoneAdapter`. The original [Workstream C](../architecture/plans/2026-07-13-mod-support-original-scope-follow-ons.md#workstream-c--sonic-3k-mod-zone-adapter) remains scope provenance, not a second adapter to build. |

The schedule above is the required disposition; none of these commitments is silently
reclassified as optional.

## Code, loading, and compatibility

| Item | Source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| Dependency-resource `ClassLoader.findResource` delegation | Phase 4 §C (attributed there to Phase 2) | Mod class loading | All checked-in code samples package their own resources and have no dependency-resource consumer. Delegation changes isolation and collision semantics. | **Keep parked** until a reproducible dependency-resource use case exists. |
| Mod objects injected into stock zone layouts | Phase 2 §B/out-of-scope | Layout mutation/object registry | The badnik sample owns its mod-zone layout; no sample needs mutation of stock placement tables. This requires persistence, load-order, and respawn conflict rules. | **Keep parked** pending a creator fixture. |
| Custom mod static rewind adapters | Phase 2 §B and shared contract §6 | Rewind/mod validator | Samples keep gameplay state on instances or session-owned services. Static adapters would expand trust, loader lifetime, and schema compatibility. | **Keep parked**; retain the compile-time-constant-only rule. |
| Hot reload | Phase 1 Non-goals; Phase 2 §A | Mod class loading/runtime lifecycle | Every sample builds a deterministic jar and activation is restart-bound. Safe loader replacement must close jars and invalidate dependency/class identity without leaving registrations or instances alive. | **Keep parked** until restart-bound code iteration is measured as the dominant adopter problem. |
| Live apply without reload | Phase 1 Non-goals; Phase 2 §A | Session ownership/mod registration | No maintained sample needs mutation of a running mod registration graph. Applying code/data in place must reconcile active audio, saves, objects, providers, and gameplay references atomically. | **Keep parked** until a concrete sample defines which state may survive apply. |
| Standalone-game patch stacking and launch-request choke-point amendment | Phase 3 §B3; Phase 4 §C | Module resolution/manifest | One global `GameId.STANDALONE` cannot safely match patches; the sample needs no stacking. Requires code-string identity plus new manifest vocabulary. | **Keep parked** pending two real standalone mods that need composition. |
| Deterministic streamed-PCM capture/replay | Root design §4; Phase 1 non-goals | Audio recording/replay | Current policy deliberately keeps streams out of the SMPS command timeline and suppresses one-shots during rewind. No sample requests deterministic audio capture. | **Keep parked**; any proposal must preserve comparison-only traces. |
| A future breaking Mod API transition | Root design §2 | Mod API governance | Mod API 0.7 is the unpublished mutable candidate and no baseline has yet been published. After a first publication, an incompatible successor requires migration guidance and an explicit compatibility decision. | **Keep parked** until an identified incompatibility cannot be added safely. |

## Character and presentation polish

| Item | Source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| Deeper moveset surgery beyond the ability pre-dispatch hook | Phase 3 §A6 narrowing | Playable movement/mod API | The character sample demonstrates distinct physics and one ability seam without new ground-state machines. Broad hooks expose fragile runtime internals. | **Keep parked** until a concrete character design cannot use the current seam. |
| Mod-supplied super-form art/behavior | Phase 3 §A6 | Super-state/playable art | The sample explicitly disables super form. Enabling it needs transformation rules and alternate art lifecycle. | **Keep parked** pending a complete super-form sample proposal. |
| Playable container v3 HUD icon slot | Phase 3 §A6; shared contract §5 | HUD/playable container | The character sample uses the deterministic host-game icon fallback. A new slot requires confirming the stock icon source, defining dimensions/palette ownership, and recutting GGFP compatibly. | **Keep parked** until art-source research proves the icon is per-character and a sample needs it. |
| Playable container v3 data-select portrait slot | Phase 3 §A6; shared contract §5 | Data select/playable container | The sample documents the deterministic Sonic-base composed-portrait fallback. A portrait payload needs dimensions, palette/composition rules, fallback behavior, and a compatible GGFP v3. | **Keep parked** until a maintained character requires distinct data-select art. |
| Mod-supplied standalone title art | Phase 3 §B3 | Master title/rendering | The standalone sample's text tile is functional. Art adds format, sizing, fallback, and VRAM ownership rules. | **Keep parked** pending visual identity feedback. |
| Standalone roster-selection UI | Phase 3 §B3 | Master title/launch config | The standalone sample registers a team but launches the module default. A selector needs persistence and accessible input/layout design. | **Keep parked** pending a game with multiple player-selectable rosters. |
| Pattern-window manager UI beyond the count | Phase 4 §C (Phase 2 pattern-budget polish) | Mod manager/graphics | Samples fit within declared windows and CI catches budget errors. Visualizing assignments is diagnostic polish, not a current blocker. | **Keep parked** until budget failures appear in creator reports. |
| Tails-style secondary/appendage sprites for mod characters | Phase 3 §A6 non-goals | Playable art/sprite lifecycle | The character sample uses no appendage. Although GGFP has an optional appendage section, stock creation remains character-specific and needs an owner-safe lifecycle contract. | **Keep parked** pending a character sample that needs an independently animated attachment. |
| Rewind capture for mod-character subclass fields | sample-platformer Task 4 review (2026-07-13) | Player rewind/mod API | `AbstractPlayableSprite.captureRewindState()`/`PlayerRewindExtra` was a closed hand-enumerated record with no subclass extension point, so mod-character ability fields (e.g. a double-jump latch) went stale on keyframe-exact seeks and cached-segment scrubs; only re-simulated seeks re-derived them. Fixed by an overridable `captureSubclassRewindState()`/`restoreSubclassRewindState(...)` pair carried as an opaque `PlayerRewindExtra.subclassExtra()` payload (mirroring `sidekickCpuExtra`), now part of Mod API 0.7 and documented in `characters.md`. The Bolt sample (`BoltCharacter`) migrated its double-jump latch to the production round trip. | **Delivered** (2026-07-14, mod-gap-fixes) — see `TestPlayableSubclassRewind` and `TestSamplePlatformerIntegration#exerciseDoubleJumpAndRewindLatch`. |
| Mod-zone title-card letter art | Phase 2 resolved-contract polish | Title cards/mod art | Sonic 2 mod zones currently use the explicit skip/fallback because ROM letter art may not cover names. New art needs glyph/container, layout, and VRAM ownership rules. | **Keep parked** pending creator demand distinct from standalone master-title art. |

## Standalone-game expansion

| Item | Source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| Standalone special stages | Phase 3 §B6 | Standalone module/special-stage framework | The acceptance game proves the normal-zone slice only. Special stages have game-specific entry, result, emerald, render, and save semantics. | **Keep parked** pending a standalone design requiring a special stage. |
| Standalone bonus stages | Phase 3 §B6 | Standalone module/bonus-stage framework | No maintained standalone sample requests checkpoint bonus-stage routing. Bonus stages have a separate checkpoint/reward lifecycle and provider contract. | **Keep parked** pending a standalone design requiring a bonus stage. |
| Cross-game donation to/from standalone games | Phase 3 §B6 | Data select/assets | No-ROM ownership conflicts with ROM-backed donation assumptions. The sample is self-contained. | **Keep parked** pending an explicit asset-licensing and capability model. |
| Standalone trace recording | Phase 3 §B6 | Trace launcher/catalog | Existing traces are stock-game reference comparisons; no canonical external reference exists for arbitrary standalone content. | **Keep parked**; ordinary deterministic tests remain available. |
| Per-standalone launch-config persistence | Phase 3 §B6 | Saves/launch config | The sample uses namespaced slot 1 and default team successfully. Additional state needs versioned, owner-scoped schema. | **Keep parked** until a sample has meaningful launch options. |
| Full standalone data-select presentation | Phase 3 §B6 | Data select/UI | New/Continue covers the acceptance path. Reusing stock presentation risks false ROM assumptions. | **Keep parked** pending a standalone-specific UX proposal. |
| Standalone `registerObjectArt` engine wiring | sample-platformer Task 5 review (2026-07-13) | Mod runtime/standalone modules | `ModContext.registerObjectArt` sheets were prepared into `ModRegistrationPlan.preparedObjectArt()` but silently dropped for standalone owners — `OwnerAwareStandaloneModule.wrap` was a passthrough with no `ModArtOverlayProvider` decoration, so the published API was a no-op trap forcing every standalone author to hand-roll a provider. Fixed by wiring the decoration into the standalone wrap, mirroring `ModBackedGamePatch.getObjectArtProvider()` (falls back to an empty base provider when the delegate itself returns `null`); the `sample-platformer` module's hand-rolled provider was deleted and migrated to the engine path as the regression fixture. | **Delivered** (2026-07-14, mod-gap-fixes) — see `TestStandaloneObjectArtWiring` and `TestSamplePlatformerIntegration#zapBugPatrolsSpringPadLaunchesAndBothRecreateForRewind`. |

## Audio and authoring formats

| Item | Source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| MP3 decoding | Root decisions/§4; Phase 1 non-goals | Audio codecs | WAV/OGG cover all samples; MP3 currently fails clearly. A decoder adds distribution, native-image, seek, and licensing maintenance. | **Keep parked** until real assets cannot reasonably be supplied as WAV/OGG. |
| Underwater low-pass filtering for streams | Root §4 optional polish | Streamed audio | The music sample validates routing/looping, not underwater mixing. Filtering adds state transitions and parity questions. | **Keep parked** pending audible acceptance criteria. |
| Authoring new SMPS/VGM chip music | Root §4 | Audio tooling | Mods can reference stock SMPS ids or ship streamed tracks. New chip-music authoring is a separate compiler/toolchain. | **Drop** from the mod-support roadmap; pursue as an independent audio-tooling proposal. |
| In-engine solid-profile shape editor | Phase 4 §A; shared contract §7 | Editor/collision-profile tooling | TMX intentionally imports profile ids and custom profiles can be supplied as exact SDK binaries. An editor must expose height, width, angle, validation, preview, and binary ownership without corrupting shared indices. | **Keep parked** until profile binaries are the dominant measured sample friction. |
| `TOP_SOLID`/`LEFT_RIGHT_BOTTOM_SOLID` TMX authoring | Phase 4 §A; shared contract §7 | TMX/collision descriptor tooling | Maintained TMX samples need only `NO_COLLISION` and `ALL_SOLID`. Additional modes require an unambiguous per-cell authoring vocabulary and golden descriptor-bit fixtures. | **Keep parked** until a level fixture requires either asymmetric mode. |
| Tiled export/round-trip | Phase 4 Non-goals | Level interchange tooling | Phase 4 requires deterministic import only. Round-trip needs a source-identity model for Tiled metadata that engine assets intentionally discard. | **Drop** from the current mod roadmap; require a new round-trip data-model proposal. |
| Tiled tileset/pattern-level editing integration | Phase 4 Non-goals | Level/art authoring tooling | The maintained path treats the tileset image as converter input and already covers bulk layout authoring. Deeper integration would duplicate palette/pattern editing and ownership rules across tools. | **Drop** from the current mod roadmap; require a separate editor-integration proposal. |

## Editor and documentation follow-ons

| Item | Source | Owner | Evidence and cost/risk | Verdict |
|---|---|---|---|---|
| S3K editor runtime re-apply | Phase 0 §C/non-goals; Phase 2 out-of-scope | Editor/S3K overlays | Persistence is supported, but live re-apply is explicitly blocked because S3K overlays and registries need coordinated rebuild. No gallery sample depends on it. | **Keep parked** until the S3K zone-adapter design can define safe rebuild ownership. |
| Pattern/8×8 art painting | Phase 0 §C Non-goals | Editor art tooling | None of the five samples needs in-engine pixel editing; converter/Tiled inputs cover shipped authoring. This requires palette-line validation, pattern ownership, undo, persistence, and VRAM-safe preview. | **Keep parked** on the editor roadmap, not as a mod-release blocker. |
| Map-write block-flag editing | Phase 0 §C Non-goals | Editor block/map tooling | No sample requires editing the remaining map-write flags. The UI must define flag semantics, copy-on-write, persistence, undo, and visual feedback before exposing them. | **Keep parked** on the editor roadmap pending a concrete flag-editing fixture. |
| Free camera pan/zoom | Phase 0 §C Non-goals | Editor camera/input UX | The samples can be authored through current focus/viewport behavior and Tiled. Pan/zoom needs coordinate transforms, input arbitration, bounds, and overlay hit-testing changes. | **Keep parked** on the editor roadmap pending measured navigation friction. |
| Remappable editor keys | Phase 0 §C Non-goals | Editor input/configuration | No sample or adopter report identifies the hardcoded editor bindings as a blocker. Remapping needs a persisted schema, conflict rules, defaults, migration, and accessible discovery. | **Keep parked** on the editor roadmap pending creator demand. |
| Mod zones in debug-only level-select screens | Phase 2 §D out-of-scope | Debug UI/zone registry | Normal progression, save, data-select, and title-card routes are registry-driven; only developer selectors retain stock lists. | **Keep parked** until mod-zone debugging shows this is more useful than direct launch/headless tests. |
| External mods in trace picker/test mode | Root design §7 unsupported policy | Trace/test external-content policy | Deterministic modes deliberately gate filesystem discovery and stock reference comparisons must stay mod-free. Checked-in sample tests load controlled fixtures explicitly. | **Drop** from the mod roadmap; a future deterministic fixture-injection proposal must not weaken the external-content gate. |
| Hosted modding documentation site | Phase 4 §B narrowing | Documentation/release engineering | The checked-in handbook is complete and link-guarded. Hosting adds publication, versioning, search, deployment, and ownership work without changing creator content. | **Drop** from Phase 4; reconsider only as a separately owned release-engineering decision. |
| Generated format-reference machinery | Phase 4 §B and Non-goals | Documentation/tooling | Exact constants are cited and the five CI-built samples expose drift. A generator would add templates and maintenance before any observed manual-doc mismatch. | **Drop** from Phase 4; require evidence that the landed guards fail to prevent drift. |

## Sweep reconciliation

Several hits name phased work already delivered on `next`: ordered additive patch
composition (which superseded the KiS2 single-patch non-goal), compiled code mods,
art overrides, the S2 mod-zone path, characters, standalone games, standalone
one-shot SFX, Tiled import, and the in-repo handbook/gallery. They are not duplicated
in this backlog. Optional manifest/audio fields, `Optional` Java return types,
later-wins ordering, later lifecycle callbacks, unknown optional GGFP sections, and
unsupported-hostile-input rejection/warnings for unsupported engine references are
normative landed contracts, not deferrals. Phase 4's GUI-implementation non-goal is
owned by the [GUI tooling evaluation](GUI_TOOLING_EVALUATION.md), not silently omitted
from this triage. The KiS2
content follow-on belongs to its own feature roadmap rather than mod support. Phase 0
blank-slate/new-zone authoring was superseded by the Phase 2 mod-zone/scaffold path;
the S1 adapter remains scheduled, while the bounded S3K adapter is delivered as
recorded above.
