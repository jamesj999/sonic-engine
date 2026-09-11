# Runbook: Add ROM-Backed Art, Mappings, DPLCs, or PLC Registration

For the most error-prone asset path: object/badnik art, sprite mappings, DPLCs, and PLC registration. Most sprite corruption, wrong-half (S3) addresses, bad mapping offsets, and PLC overlap mistakes happen here.

Companion skills: [`.agents/skills/plc-system/SKILL.md`](../../../.agents/skills/plc-system/SKILL.md) (cross-game), [`.agents/skills/s3k-plc-system/SKILL.md`](../../../.agents/skills/s3k-plc-system/SKILL.md) (S3K specifics).

---

## 0. Non-negotiables

1. **ROM-only runtime assets.** Art/mapping/DPLC/PLC bytes come from the user ROM via the loader. Never read them from `docs/` disassembly — labels/offsets only.
2. **S3K: prefer S&K-side addresses.** `sonic3k.asm`, `< 0x200000`. When a label returns both `sonic3k.asm` and `s3.asm` hits, pick `sonic3k.asm`. If only `s3.asm` hits, re-search with variants first; but if there is genuinely no S&K equivalent, some S3K objects reference S3-half assets directly — use the `s3.asm` reference after verifying (rare; don't loop forever).
3. **VDP sprite tile order is column-major:** `tileIndex = column * heightTiles + row`. H-flip draws last column first; V-flip bottom row first.

---

## 1. Discovery commands

`RomOffsetFinder` (`src/main/java/com/openggf/tools/disasm/RomOffsetFinder.java`). Compression type is encoded in the label suffix (e.g. `_KosM`, `_Nem`); the tool auto-infers.

```powershell
# Find art / mapping / DPLC offsets (S3K: verify the hit is from sonic3k.asm)
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k find ArtNem_<Name>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k find Map_<Name>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k find DPLC_<Name>" -q

# Verify the offset decompresses cleanly to a sane size
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k verify <Label>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k test <offset> <COMPRESSION>" -q

# Inspect the PLC list for a zone/act
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k plc <PlcLabel>" -q
```

`RomOffsetFinder` is also callable programmatically: `new RomOffsetFinder(disasmPath, romPath, GameProfile.sonic3k())` then `findOffset`, `verify`, `testAutoDetect` (return `OffsetFinderResult` / `VerificationResult` / `CompressionTestResult`).

---

## 2. Files to inspect / touch by game

### S3K (most structured)
| File | Role |
|------|------|
| `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java` | Per-zone/act manifest. `StandaloneArtEntry` (badnik/boss art decompressed from ROM) vs `LevelArtEntry` (references patterns already in level VRAM). `getPlan(zoneId, actId)` returns the `ZoneArtPlan`. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java` | `buildLevelArtSheetFromRom(mappingAddr, artTileBase, sheetPalette)` etc. Extract `artTileBase` + `palette` from the object's `make_art_tile()` call. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java` | Art key constants. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | S&K-side ROM offsets + sizes. |

**Choosing StandaloneArtEntry vs LevelArtEntry:**
- Badnik/boss art that is decompressed and uploaded for the object → `StandaloneArtEntry` (artAddr, compression, artSize, mappingAddr, palette, dplcAddr).
- Object that reuses tiles already in the level's VRAM (springs, spikes, traversal pieces) → `LevelArtEntry` (mappingAddr or -1 for hardcoded, artTileBase, palette, builderName).

### S2 / S1
- S2: `S2SpriteDataLoader.loadMappingFrames(reader, mappingAddr)`; add mapping addr to `Sonic2Constants.java`; loaders in `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java`.
- S1: `Sonic1ObjectArt.buildArtSheet(...)` / `S1SpriteDataLoader.loadMappingFrames(...)`; offsets in `Sonic1Constants.java` (verify against compiled ROM — S1 labels are unreliable).

---

## 3. Virtual pattern ID note

The atlas extends the 11-bit VDP space. Each category claims a non-overlapping base (level tiles `0x00000`, objects `0x20000`, HUD `0x28000`, sidekicks `0x38000+`, etc.). When adding a new pattern category, pick a non-overlapping `*_PATTERN_BASE`. See `PatternAtlas`, `DynamicPatternBank`, `GraphicsManager.renderPatternWithId(...)`, and [`docs/status/known-discrepancies.md`](../../status/known-discrepancies.md).

---

## 4. Required guard tests + focused regression tests

```powershell
# S3K art/mapping/PLC:
mvn "-Dtest=TestSonic3kPlcArtRegistry" test            # entry presence, palette, tile bounds, frame/piece sanity
mvn "-Dtest=TestPatternSpriteRendererCorruptionGuard" test  # rejects pathological frame geometry (>80 pieces)

# Keep-green S3K loaders:
mvn "-Dtest=TestSonic3kLevelLoading" test
mvn "-Dtest=TestSonic3kDecodingUtils" test
```

`TestSonic3kPlcArtRegistry` sanity limits: ≤256 frames, ≤80 pieces/frame, ≤512 tiles/frame, ≤1024px span, ≤2048px abs piece offset. Tests JUnit 5 only.

---

## 5. Common failure signatures → fix

| Signature | Cause | Fix |
|-----------|-------|-----|
| `TestPatternSpriteRendererCorruptionGuard` / on-screen sprite garbled | Mapping addr points to wrong data — usually the **S3 half** | Re-find with `--game s3k`; pick `sonic3k.asm`; `verify` the offset. |
| `TestSonic3kPlcArtRegistry`: missing entry | Object's art not in `getPlan` for its zone/act | Add `StandaloneArtEntry`/`LevelArtEntry`. |
| `TestSonic3kPlcArtRegistry`: bad palette | Wrong palette line | Match the `make_art_tile()` palette (0-3). |
| `TestSonic3kPlcArtRegistry`: tile OOB / pieceCount>80 / span too large | Wrong mapping offset or wrong format | Re-verify mapping addr; check `MappingFormat`; confirm tile base. |
| `verify` reports tiny/huge decompressed size | Wrong compression or wrong offset | Try `test <offset> <type>` with correct compression; re-find label. |
| PLC overlap (tiles clobbered) | Two entries target the same VRAM tile base | Reassign `artTileBase` to a free range. |

---

## 6. ROM / disassembly citation expectations

For each constant: cite the `sonic3k.asm` (or `s2disasm`/`s1disasm`) label, the verified ROM offset, the compression type, and — for S3K — an explicit note that the address is the S&K-side one. Record the `make_art_tile()` art-tile base and palette you extracted.

---

## 7. Documentation & commit-trailer obligations

- `CHANGELOG.md` + `Changelog: updated`.
- `docs/S3K_KNOWN_DISCREPANCIES.md` (S3K) or `docs/status/known-discrepancies.md` (S1/S2) + the matching trailer if a parity gap changes (e.g. virtual pattern ID range notes).
- Skill edits mirrored across `.agents/skills/` and `.claude/skills/`, `Skills: updated`.
- Fill all trailers; never `--no-verify`.
