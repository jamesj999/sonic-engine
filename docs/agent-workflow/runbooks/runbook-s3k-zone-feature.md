# Runbook: Bring Up an S3K Zone Feature

For implementing one S3K zone feature category: **events** (Dynamic_Resize), **parallax** (Deform), **animated tiles** (AniPLC), or **palette cycling** (AnPal). These plug into the runtime-owned framework stack — do not introduce zone-local registries.

Companion skills:
- [`.agents/skills/s3k-zone-analysis/SKILL.md`](../../../.agents/skills/s3k-zone-analysis/SKILL.md) — produce a structured zone analysis spec first.
- [`.agents/skills/s3k-zone-bring-up/SKILL.md`](../../../.agents/skills/s3k-zone-bring-up/SKILL.md) — orchestrates the whole bring-up.
- [`.agents/skills/s3k-zone-events/SKILL.md`](../../../.agents/skills/s3k-zone-events/SKILL.md), [`s3k-parallax`](../../../.agents/skills/s3k-parallax/SKILL.md), [`s3k-animated-tiles`](../../../.agents/skills/s3k-animated-tiles/SKILL.md), [`s3k-palette-cycling`](../../../.agents/skills/s3k-palette-cycling/SKILL.md).
- [`.agents/skills/s3k-zone-validate/SKILL.md`](../../../.agents/skills/s3k-zone-validate/SKILL.md) — visual validation.

---

## 0. Hazards (same as S3K objects)

- **Zone set:** `S3kZoneSet.forZone(zone)` (S3KL 0-6, SKL 7-13).
- **Prefer S&K-side addresses** for every offset (`sonic3k.asm`, `< 0x200000`). Run `RomOffsetFinder --game s3k`. Rare exception: if no S&K equivalent exists, an `s3.asm` reference may be the one the object uses (verify; don't loop).
- **ROM-only runtime assets** — animated-tile bytes, palettes, deform tables come from the ROM loader, not `docs/skdisasm/`.
- **No carve-outs:** express new zone behaviors as `ZoneRuntimeState` predicates / ROM-state conditions, never `if (zone == AIZ)`.

---

## 1. Discovery commands

```powershell
# Event / resize routine
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search <ZONE>_Resize" -q
# Parallax / deform
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search <ZONE>_Deform" -q
# Animated tiles (AniPLC) and palette cycling (AnPal) tables
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search AnPal_<ZONE>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k verify <Table_Label>" -q
```

Disassembly anchors: AnPal routines live in `docs/skdisasm/.../sonic3k.asm` (research only — bytes load from ROM).

---

## 2. Files to inspect

| Feature | Framework / file |
|---------|------------------|
| Events | `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java` + per-zone handlers in `src/main/java/com/openggf/game/sonic3k/events/` (`Sonic3kAIZEvents`, `Sonic3kCNZEvents`, `Sonic3kHCZEvents`, `Sonic3kICZEvents`, `Sonic3kMGZEvents`, `Sonic3kMHZEvents`). Base: `src/main/java/com/openggf/game/AbstractLevelEventManager.java`. |
| Parallax / deform | `level.scroll.compose` (`ScrollEffectComposer`, `DeformationPlan`, `WaterlineBlendComposer`). |
| Animated tiles | `AnimatedTileChannelGraph`; `Sonic3kPatternAnimator`. |
| Palette cycling | `PaletteOwnershipRegistry`; `Sonic3kPaletteCycler`. |
| Palette mutation (one-shot writes in _Resize) | distinct from cycling — handled in the event handler, arbitrated via `PaletteOwnershipRegistry`. |
| Zone runtime state | `ZoneRuntimeRegistry` / `ZoneRuntimeState` (typed adapters over event/state bytes). |
| Layout edits | `ZoneLayoutMutationPipeline` (see [`runbook-gameplay-level-mutation.md`](runbook-gameplay-level-mutation.md)). |

See [`AGENTS_S3K.md`](../../../AGENTS_S3K.md) for the per-zone inventory and the cycling-vs-mutation distinction.

---

## 3. Files likely touched

- Event handler: new/edited `src/main/java/com/openggf/game/sonic3k/events/Sonic3k<ZONE>Events.java`; register in `Sonic3kLevelEventManager.java`.
- ROM offsets: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`.
- Palette cycling: register channels in `Sonic3kPaletteCycler`.
- Animated tiles: register AniPLC scripts in `Sonic3kPatternAnimator`.
- Parallax: deform handler wired through `ScrollEffectComposer`.
- Tests + `CHANGELOG.md`, `docs/S3K_KNOWN_DISCREPANCIES.md`.

---

## 4. Implementation rules

- Route ALL behavior through the runtime-owned framework (`ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`, `ScrollEffectComposer`, `SpecialRenderEffectRegistry`, `AdvancedRenderModeController`).
- Express zone-specific needs as `ZoneRuntimeState` predicates (e.g. `requiresFullWidthBgTilemap()`), not manager lookups or zone-id branches.
- Honor ROM state hooks: e.g. camera vertical cap raised by ROM `Fast_V_scroll_flag` — model the flag, not the zone.
- Keep palette **cycling** (timer-driven, every frame) separate from palette **mutation** (one-shot camera-triggered writes).

---

## 5. Required guard tests + focused regression tests

```powershell
mvn "-Dtest=TestS3kAiz1SkipHeadless" test
mvn "-Dtest=TestSonic3kLevelLoading" test
mvn "-Dtest=TestSonic3kBootstrapResolver" test
mvn "-Dtest=TestSonic3kDecodingUtils" test
mvn "-Dtest=Test<Zone><Feature>..." test "-Ds3k.rom.path=s3k.gen"
```

Zone-state guards to keep green where relevant: `S3kRuntimeStateReadGuard`, `S3kAizWriteBridgeGuard`, `S3kTransitionBridgeGuard`, `S3kHczPaletteOwnershipMigrationGuard`, `TestZoneEventRuntimeAccessGuard`. Visual validation via the `s3k-zone-validate` skill.

---

## 6. Common failure signatures → fix

| Signature | Fix |
|-----------|-----|
| Palette flicker / wrong owner wins | Register through `PaletteOwnershipRegistry` with correct precedence; don't write VRAM palette directly. |
| BG tilemap too narrow / scroll artifacts | Express need as `ZoneRuntimeState.requiresFullWidthBgTilemap()` predicate. |
| Zone-id branch flagged in review | Replace with a `ZoneRuntimeState` predicate / ROM-state condition. |
| Animated tiles not updating | Register the AniPLC channel in `AnimatedTileChannelGraph` / `Sonic3kPatternAnimator` with gating condition. |
| `maxChunkPatternIndex > patternCount` log | Known dynamic-art parity gap; note in PR. |

---

## 7. ROM / disassembly citation expectations

Cite `sonic3k.asm` labels + verified S&K-side offsets for each table. Note when a label also exists in `s3.asm` and that you chose the S&K-side address. Never load table bytes from `docs/`.

---

## 8. Documentation & commit-trailer obligations

- `CHANGELOG.md` + `Changelog: updated`.
- `docs/S3K_KNOWN_DISCREPANCIES.md` + `S3K-Known-Discrepancies: updated` for parity gaps.
- Skill changes mirrored across `.agents/skills/` and `.claude/skills/`, `Skills: updated`.
- Fill all trailers; never `--no-verify`.
