# GamePatch Framework + Knuckles in Sonic 2 (Lock-On) — Design

**Date:** 2026-06-12
**Status:** Approved (brainstorming session)
**Roadmap anchor:** `ROADMAP.md` v0.7 — "Native Knuckles in Sonic 2 support without S3K donation."

## Goal

Support **Knuckles in Sonic 2** (the official game produced when the Sonic & Knuckles
cartridge locks onto Sonic 2) as a faithful patch target, implemented from the
s2disasm `knuckles-in-sonic-2` branch diffs against stock Sonic 2 — not inferred
from S3K cross-game donation.

To do this in an architecturally sound, reusable way, introduce a new
**GamePatch / GameVersion** framework: a code overlay that sits on top of an
existing `GameModule` and forms the basis for future ROM-hack support. KiS2 is
the framework's first consumer.

## Scope

**This design covers** the GamePatch framework plus a *playable core slice* of KiS2:

- Knuckles physics from the KiS2 branch diffs (profile constants + feature set)
- Glide/climb via the shared feature-flag-gated moveset code
- Knuckles art loaded from the S&K ROM data (logical ROM, see below)
- Faithful-default roster (Knuckles alone), with config-driven sidekick override allowed
- Monitor / life-icon art swaps
- Documented spawn/object-placement changes from the branch diff

**Explicitly deferred** (catalogued in the diff doc, tracked as follow-ups):
title screen and level-select flow, special stages with Knuckles, Super Knuckles,
2P-mode differences, KiS2 trace-replay capture.

## Decisions made during brainstorming

1. **Presentation:** Knuckles is a character option on Sonic 2's existing
   launch-config screen. Selecting Knuckles as the S2 main character
   automatically activates the KiS2 patch. (No separate game entry.)
2. **Donation coexistence:** Cross-game donation remains a separate, orthogonal
   feature (it brings data-select/save integration and other extras). When both
   apply, **the patch wins gameplay**: in-level physics, art, abilities,
   monitors, and spawns always come from the patch; donation keeps layering its
   data-select/save extras.
3. **Approach:** Module decorator framework (Approach A), with overlay-style
   data maps used *inside* the patch for fine-grained diffs (B-style data,
   following the existing `LevelResourcePlan` overlay precedent). Rejected:
   per-provider `GameVersion` overlays everywhere (scatters ownership, invasive
   to stock providers) and a bare `Sonic2KGameModule` subclass (no reusable
   framework).
4. **Sidekick:** The patch sets the faithful default (no sidekick), but does
   not hard-disable sidekicks — the existing config/launch-profile sidekick
   selection stays authoritative. A configured sidekick is an intentional
   divergence documented in `docs/status/known-discrepancies.md`. Trace work always
   uses the faithful default.
5. **ROM prerequisite is logical, not physical:** the patch needs "Sonic &
   Knuckles data available" in *any* form — a standalone 2MB S&K image or the
   combined "Sonic and Knuckles & Sonic 3" ROM (S&K occupies
   `0x000000–0x1FFFFF`, so S&K-cart-relative addresses equal combined-ROM
   addresses in that range and the combined reader serves logical S&K reads
   directly, with a bounds guard).

## Architecture

### New package: `com.openggf.game.patch`

#### `GamePatch` (interface)

The contract a ROM hack implements:

- `id()` — stable identifier, e.g. `"kis2"`.
- `displayName()` — e.g. `"Knuckles in Sonic 2"`.
- `baseGame()` — the `GameId` this patch applies to (S2 for KiS2).
- `activation()` — a predicate over the launch request. For KiS2: requested
  main character is `KNUCKLES`. The framework evaluates the predicate; patches
  never inspect global state themselves.
- `romPrerequisites()` — logical ROM identities that must be resolvable
  (KiS2: S&K data).
- `apply(GameModule base, PatchContext ctx)` — returns the patched module.

#### `PatchContext`

Explicit dependencies handed to `apply()`: the primary ROM reader, readers for
each resolved logical ROM prerequisite, and configuration access. Consistent
with the post-runtime-ownership style (no global lookups inside patches).

#### `DelegatingGameModule`

An explicit forwarding implementation of `GameModule`: every method delegates
to the wrapped base module. Patches extend it and override only the surfaces
they change.

Chosen over a reflective `java.lang.reflect.Proxy`: compile-time safety and
clean stack traces. The interface-growth hazard (a method later added to
`GameModule` with a default body would not be forwarded, silently diverging
the patched module from its base) is closed by a reflection guard test —
`TestDelegatingGameModuleCoversInterface` asserts the delegator declares every
`GameModule` method, following the repo's scanner-guard idiom
(`TestObjectServicesMigrationGuard`).

#### `GamePatchRegistry`

Patches are registered per base game at module-registration time. The registry
answers two questions:

- `availableCharacters(GameId)` — which extra main characters patches can back
  for a base game (used by launch-profile availability, below).
- `resolveModule(GameModule base, GameplayLaunchRequest request)` — if an
  applicable patch exists for (base game, request) and its ROM prerequisites
  are satisfied, return `patch.apply(base, ctx)`; otherwise return the base
  module unchanged.

`SessionManager` is **not** patch-aware and its signature does not change: it
continues to receive an already-constructed `GameModule`. Patch resolution
happens at the existing module-construction choke points *before*
`openGameplaySession` is called — the master-title launch flow,
`Engine.launchGameplayFromDataSelect()`, `TraceReplaySessionBootstrap`, and
`HeadlessGameBoot` — each routing through `GamePatchRegistry.resolveModule()`.
Because `GameModuleRegistry.getCurrent()` already resolves the module from the
session, every downstream call site sees the patched module transparently.

The patched module self-identifies: `DelegatingGameModule` exposes a
`patchId()` accessor (framework marker interface), so `WorldSession`, save
identity, and trace bootstrap code can interrogate whether and which patch is
active without new session plumbing.

#### Launch request and availability plumbing

The current launch path would silently defeat patch activation:
`LaunchProfile.mainCharacterValues()` only offers Knuckles for S2 when the
cross-game donor is S3K, and `sanitizedFor()` strips any character not in that
list. The framework therefore extends `LaunchProfile` availability:

- The per-entry character value lists become the **union** of donor-provided
  characters (existing behavior) and patch-provided characters from
  `GamePatchRegistry.availableCharacters()` (present only when the patch's ROM
  prerequisites are currently satisfied).
- `sanitizedFor()` consults the same union, so a patch-backed Knuckles
  selection survives sanitization with donation off.

A small `GameplayLaunchRequest` record (game entry, main character, sidekick,
cross-game source) is derived from the sanitized launch profile and passed to
`resolveModule()` at the choke points above. Trace bootstrap constructs the
same record explicitly so future KiS2 traces can activate the patch
deterministically.

**Save-context team sanitization.** The data-select path carries the requested
team in `SaveSessionContext.selectedTeam()`, which
`ActiveGameplayTeamResolver` prefers over config — so patch failure must not
leave an unbackable team in the session. At every choke point that builds a
session with a save context (notably `Engine.launchGameplayFromDataSelect()`),
after `resolveModule()` returns, the launch path validates the requested team
against the same availability union used by `LaunchProfile` (stock roster +
donor-provided + patch-provided characters, as currently resolvable). If the
team's main character is unavailable — e.g. a saved Knuckles slot when S&K
data has disappeared and donation is off — the *session* team is sanitized to
the stock main character (Sonic) and unavailable sidekicks are dropped, with a
logged warning. The save slot on disk is never rewritten; only the
session-owned `SaveSessionContext` team is replaced, so the slot launches as
Knuckles again once the ROM returns.

#### Logical ROM resolver

A small resolver maps a logical ROM identity (a new `LogicalRom` enum, first
member `SK`) to a byte source from whatever physical ROM images are present.
`PatchContext` hands patches a reader for the *logical* ROM; the patch never
knows which physical file backed it.

Grounding in the current code (`RomManager.resolveRomForGame` knows only
s1/s2/s3k; `RomByteReader` has no window/view abstraction):

- **v1 backend — combined S3K ROM (required):** S&K occupies
  `0x000000–0x1FFFFF` of the combined image, so S&K-cart-relative addresses
  equal combined-ROM addresses in that range. The resolver returns the existing
  `RomManager.getSecondaryRom("s3k")` reader directly — no window abstraction
  is needed. The resolver enforces the invariant with a bounds guard: a logical
  S&K read at an address `>= 0x200000` is a hard error (it would be reading the
  S3 half).
- **v1 backend — standalone S&K image (discrete, droppable step):** a new
  config key for an optional standalone S&K ROM path (with the required
  `ConfigCatalog` metadata, placed before the `debug.*` block), plus an S&K
  `RomDetector` header check. When configured and detected, this backend is
  preferred. If this step slips, the combined-ROM backend alone satisfies KiS2
  since the combined ROM is already the repo-standard requirement.

Future patches declare logical ROM needs the same way; split/extract/window
logic, if ever needed for an identity that does not sit at offset 0, lives in
the resolver — not in patches.

### Deliberate non-goals (YAGNI)

- **No patch chaining/stacking** — one patch per session.
- **No mid-session toggling** — patches resolve at session open, the same
  lifecycle as the module itself (teardown+rebuild already exists for editor
  mode).
- **No binary/IPS ROM patching** — a `GamePatch` is a *code* overlay that reads
  original ROM bytes from wherever they actually live.

## The KiS2 patch

### Package: `com.openggf.game.sonic2.kis2`

Lives beside the S2 module it patches (heavy use of S2 constants/providers);
implements `GamePatch`.

### Step zero: the diff catalogue (drives everything)

Before implementation: diff s2disasm `master` vs `origin/knuckles-in-sonic-2`
(branch already available locally under `docs/s2disasm`) and write
`docs/kis2/BRANCH_DIFFS.md` classifying every code/data change:

- physics constants
- player object code (glide/climb routines)
- S&K-side art/mapping/DPLC addresses
- object placement / spawn changes
- monitor and life-icon swaps
- title / level-select / special-stage changes (catalogued but deferred)
- miscellaneous

Every implementation item in the patch cites its diff entry. **When the branch
differs from S&K-native Knuckles behavior, the branch wins** — this is how the
roadmap's "do not infer this behavior from S3K donation alone" requirement is
honored.

### Physics

**Scoping model (precise):** `PhysicsFeatureSet` is and stays module-scoped —
`PhysicsProvider.getFeatureSet()` takes no character parameter and is read
module-wide (e.g. `LevelFrameContext`). That is correct here because the
patched module *is* the game variant: the KiS2 provider returns one KiS2
feature set for the whole session. Per-character differences — including a
config-added sidekick — resolve through the existing per-character paths, the
same way S3K already runs Knuckles alongside a Tails sidekick today:
`getProfile(characterType)` for movement constants and `SecondaryAbility`
(character_id branching: GLIDE for Knuckles, FLY for Tails) for abilities. No
`PhysicsProvider` API change is required.

- New `PhysicsProfile` for KiS2 Knuckles, constants transcribed from the diff
  catalogue (e.g. reduced jump velocity).
- New KiS2 `PhysicsFeatureSet` derived from `SONIC_2` with glide/climb
  capability flags enabled, returned by the patched module's provider for the
  whole session.
- The patch overrides `getPhysicsProvider()`; `getProfile("knuckles")` returns
  the KiS2 profile, and sidekick character types fall through to stock S2
  profiles.
- Glide/climb behavior reuses the shared feature-flag-gated moveset code paths.
  Any KiS2-vs-S&K behavioral deviation found in the diff gets its own
  `PhysicsFeatureSet` flag — never a version/zone/game check, per the CLAUDE.md
  carve-out rules.
- The KiS2 provider is stateless per call — it must not copy the
  `lastCharacterType` stateful pattern currently in
  `Sonic3kPhysicsProvider.getModifiers()`.

### Art

`Kis2PlayerArtProvider` loads Knuckles sprite art, mappings, and DPLCs from the
logical S&K ROM (via `PatchContext`), at addresses taken from the branch's
lock-on data references and verified with `RomOffsetFinder`. The ROM-only
runtime assets rule holds: nothing is read from `docs/`.

### Monitor / life-icon swaps

An overlay map of art keys (1-up monitor face, HUD life icon, end-of-act
signpost face if the diff confirms it) → S&K-ROM-backed loaders. Everything not
in the map falls through to stock S2 art.

### Roster / sidekick

- Faithful default: roster = Knuckles, sidekick = none; session
  `PlayerCharacter` = `KNUCKLES`.
- Config/launch-profile sidekick selection remains authoritative: an explicitly
  configured sidekick is honored through the engine's existing multi-sidekick
  support, and documented as an intentional divergence in
  `docs/status/known-discrepancies.md`.

### Spawn / placement changes

Whatever the diff catalogue surfaces (changed start positions, adjusted object
placements) is expressed as overlay data inside the patch, keyed by the same
mechanism stock placement uses. No zone carve-outs in shared code.

## Launch flow

- The launch-config screen offers Knuckles as an S2 main character whenever the
  logical S&K ROM is resolvable, via the patch-aware `LaunchProfile`
  availability union described above (donation continues to contribute its own
  candidates independently).
- Selecting Knuckles sets the requested character on the launch profile; the
  launch path derives a `GameplayLaunchRequest`, calls
  `GamePatchRegistry.resolveModule()`, and opens the session with the patched
  module; gameplay runs on it.

## Error handling

- S&K data unavailable → Knuckles is simply not offered for S2 (same pattern as
  donation options today).
- A saved launch profile or data-select slot team requests Knuckles but the ROM
  has since disappeared → the launch path falls back to stock S2 + Sonic with a
  logged warning, including sanitizing the session-owned
  `SaveSessionContext.selectedTeam()` (see save-context team sanitization
  above) so `ActiveGameplayTeamResolver` cannot resolve an unbackable Knuckles
  team on the stock module. The save slot on disk is untouched. Never a crash;
  never a silent S3K-donation substitution for gameplay.

## Testing

**Framework tests (no ROM required):**

- `TestDelegatingGameModuleCoversInterface` — reflection guard over the
  delegator (closes the interface-growth hazard).
- `TestGamePatchRegistry` — activation resolution: correct patch chosen for
  (base game, launch request); no patch when prerequisites unmet; stock module
  untouched when no patch applies.
- `LaunchProfile` availability/sanitization tests: Knuckles offered for S2 when
  the patch is available with donation off; a patch-backed Knuckles selection
  survives `sanitizedFor()`; Knuckles stripped when no patch and no S3K donor.
- Save-context team sanitization tests: a data-select load of a Knuckles-team
  slot with S&K data absent (and donation off) opens the session with a
  Sonic team in `SaveSessionContext` (verified via
  `ActiveGameplayTeamResolver`) and leaves the slot file unmodified; the same
  slot with S&K data present resolves the patched module with a Knuckles team.
- Logical-ROM resolver tests with synthetic byte arrays: combined-ROM backend
  with bounds guard (read `>= 0x200000` fails), standalone-backend preference
  and identity detection.

**KiS2 patch tests:**

- `TestKis2PhysicsProfile` following the `TestSpindashGating` /
  `TestCollisionModel` pattern (TestableSprite inner class, no ROM/OpenGL):
  profile constants match the diff-catalogue values, glide/climb flags set,
  stock S2 profile unchanged.
- ROM-gated tests (skipped when ROMs absent, like `TestRomLogic`): art loading
  sanity (mappings parse, non-empty frames), monitor/life-icon overlay keys
  resolve.
- Headless integration via `HeadlessTestRunner`: patched-S2 session boots,
  Knuckles spawns with the KiS2 profile, sidekick absent under the faithful
  default and present when configured.

All tests JUnit 5 / Jupiter only.

## Rollout order (implementation-plan spine)

1. **Diff catalogue** — `docs/kis2/BRANCH_DIFFS.md` from the s2disasm branch;
   everything downstream cites it.
2. **Framework** — `GamePatch`, `PatchContext`, `DelegatingGameModule` + guard
   test, `GamePatchRegistry`, logical-ROM resolver (combined-ROM backend +
   bounds guard; standalone-S&K backend as a discrete droppable sub-step),
   `GameplayLaunchRequest`, patch-aware `LaunchProfile` availability/
   sanitization, and `resolveModule()` wiring at the module-construction choke
   points (master title, `Engine.launchGameplayFromDataSelect()`,
   `TraceReplaySessionBootstrap`, `HeadlessGameBoot`).
3. **KiS2 physics** — profile + feature set + provider override.
4. **Knuckles art** — S&K-data loaders + player sprite integration.
5. **Behavior data** — monitor/life-icon overlays, faithful-default roster,
   spawn/placement diffs.
6. **Launch-screen wiring + docs** — CHANGELOG, KNOWN_DISCREPANCIES, guide,
   ROADMAP tick.

**Branch:** `feature/ai-game-patch-kis2` off `develop`.
