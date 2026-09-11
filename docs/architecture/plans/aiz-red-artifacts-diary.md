# AIZ Red Artifacts Diagnostic Diary

## Symptoms (persistent across 3+ fix attempts)

1. **AIZ1Tree**: Bright red rectangular blocks around/behind the palm tree where BG (sky) should show through
2. **AIZHollowTree**: Bright red vertical stripes throughout the hollow tree interior (should be dark red/brown)
3. **GUI background**: Grey behind GUI is white instead of grey — "shouldn't turn white until after AIZ1 transition"
4. All three persist whether intro is skipped or not

## Reference Images (project root)

- `AIZ1-tree-red.png` / `AIZ1-tree-rom-reference.png` — bug vs correct
- `hollow-tree-red.png` / `hollow-tree-rom-reference.png` — bug vs correct

## Fix Attempts

### Fix #1: Intro-skip palette override ($2A -> $A)
- **Hypothesis**: Skip-intro mode was loading Pal_AIZ ($2A) instead of Pal_AIZIntro ($A)
- **Change**: Override paletteIndex to $A in skip-intro path
- **Result**: FAILED — issue persists

### Fix #2: Palette offset (palettes[i+1] -> palettes[i+2])
- **Hypothesis**: S3K Normal_palette_line_2 = CRAM line 2 = engine palette[2]
- **Change**: Shifted level palette loading from palettes[1,2,3] to palettes[2,3,4?]
- **Result**: WRONG direction — made things worse

### Fix #3: Correct palette offset back to palettes[i+1], load 3 lines
- **Hypothesis**: S3K Normal_palette_line_2 = offset $20 = CRAM line 1 = engine palette[1] (1-based naming)
- **Change**: Load 3 palette lines into palettes[1,2,3]
- **Result**: Mapping is technically correct, but visual issue REMAINS

### Fix #4: VDP Register 7 backdrop color (2026-03-14)
- **Hypothesis**: Engine used palette[2].getColor(0) as backdrop instead of palette[0].getColor(0)
- **Evidence**: All three games init VDP Register 7 to $8700 = palette 0, color 0
- **Changes**: Fixed `Level.java` default and added `Sonic3kLevel.java` override
- **Result**: PARTIAL — fixed GUI text shadow to match ROM reference, but tree/hollow tree red persists
- **User feedback**: "fixes the shadow of text in the GUI to be consistent with reference ROM"

### Fix #5: VDP BG high-priority compositing overlay (2026-03-14)
- **Hypothesis**: On real VDP hardware, high-priority BG tiles render ABOVE low-priority FG tiles.
  Engine rendered ALL BG behind ALL FG. This means low-priority FG tiles with red pixels cover
  high-priority BG sky tiles, when they should be the other way around.
- **Evidence**:
  - ROM reference shows BLUE SKY between tree canopy leaves; engine shows RED BLOCKS
  - The BG sky IS visible above/around tree (BG is rendering), just not at canopy-gap positions
  - Engine already had zone-specific HTZ earthquake workaround for exact same issue
  - `writeTileDescriptor()` preserves priority bit in tilemap data (line 2484)
  - The tilemap shader already supports `PriorityPass` filtering (0=low, 1=high, -1=all)
  - The comment at `renderHtzEarthquakeBgHighOverlay()` explicitly described this layer ordering gap
  - VDP layer order: BG-low → FG-low → **BG-high** → FG-high → Sprites
  - Engine order was: BG(all) → FG-low → (HTZ-only BG-high) → FG-high → Sprites
- **Changes**:
  - Generalized `renderHtzEarthquakeBgHighOverlay()` → `renderBgHighPriorityOverlay()`
  - Removed HTZ-only zone/earthquake guard — now runs for ALL zones
  - Kept HTZ-specific VDP wrap height disable as a conditional within the method
  - Updated call site comment to describe general VDP layer ordering
- **Files changed**: `LevelManager.java` (lines ~1514-1517 call site, ~2093-2164 method)
- **Result**: FAILED — AIZ BG tiles are ALL low-priority, so the overlay renders nothing for AIZ.
  Not the cause; the red pixels come from FG tile data, not missing BG compositing.

### Fix #6: clearTrailing=false for terrain swap overlays (2026-03-14)
- **Hypothesis**: `applyPatternOverlay()` and `applyChunkOverlay()` used default `clearTrailing=true`,
  which zeroed out all patterns above the overlay range (775-2015). This destroyed PLC-loaded
  patterns (character art, spikes/springs, zone art from PLCs 0x0B, 0x01, 0x4E).
- **Evidence**: ROM `Kos_Decomp` writes secondary data at the primary-data offset without
  clearing trailing VRAM. PLCs loaded during `addLevelPlcPatternOps()` contribute patterns
  775-2015 that must survive the terrain swap.
- **Changes**: Explicit `clearTrailing=false` on both overlay calls in `applyMainLevelOverlays()`
- **Files changed**: `AizIntroTerrainSwap.java` (lines 110-111)
- **Result**: PARTIAL — preserves PLC patterns, but red blocks persist because the overlay
  was re-applying the wrong art data (see Fix #7)

### Fix #7: Read entry 0's secondary art/blocks, not entry 26's (2026-03-14)
- **Hypothesis**: `loadOverlayData()` read secondary art/blocks from LLB entry 26 (intro)
  instead of entry 0 (normal AIZ1). The terrain swap is supposed to replace intro art with
  normal AIZ1 art, but was re-applying entry 26's own secondary data (a no-op).
- **Result**: REVERTED — this was a wrong direction. `loadOverlayData()` correctly reads
  entry 26's secondary because that IS the `AIZ1_8x8_MainLevel_KosM` data. The terrain swap
  overlay is supposed to apply MainLevel data (= entry 26's secondary) on top of whatever
  secondary art was loaded initially. The problem was that skip-intro prevented the swap
  from firing entirely (see Fix #8).

### Fix #8: Apply terrain swap in skip-intro init (2026-03-14)
- **Hypothesis**: `skipToMainLevelPhase()` set `mainLevelTerrainSwapAttempted=true`, preventing
  the terrain swap from ever firing. But on real hardware with level select, entry 0's secondary
  art loads initially (has pixel-15 at canopy positions → bright red with Pal_AIZ), then the
  terrain swap fires at cameraX >= $1400 — replacing entry 0's secondary with MainLevel art
  (from entry 26's secondary) BEFORE the player reaches the tree area ($2400+).
  Skip-intro's camera starts past $1400, so the swap should fire immediately during init.
- **Evidence**:
  - `TestAizPostSwapDiagnostic.scanLoadedLevelWithPalAiz()` WITHOUT terrain swap:
    168,544 red pixels, 50 sources, ALL `pri=true` (visible above everything)
  - Same test WITH terrain swap applied during init:
    2,750 red pixels, 50 sources, ALL `pri=false` (behind high-priority canopy tiles)
  - 98.4% reduction. Remaining red is in MainLevel art itself at trunk/root positions
    (patterns 0x165-0x1DC, chunks 370-459), all low-priority — matches real hardware behavior.
  - ROM flow: `AIZ1_Resize` routine 2 at cameraX >= $1400 queues `AIZ1_8x8_MainLevel_KosM`
    → VRAM at $0BE and `AIZ1_16x16_MainLevel_Kos` → blocks at chunkOffset
- **Changes**: Modified `skipToMainLevelPhase()` to call `AizIntroTerrainSwap.applyMainLevelOverlays()`
  before setting flags, matching what real hardware does when level select starts past $1400
- **Files changed**: `AizPlaneIntroInstance.java` (skipToMainLevelPhase method)
- **Result**: DATA SUCCESS, VISUAL FAILURE — headless diagnostic shows 98.4% reduction (168K→2.7K)
  with remaining pixels all `pri=false`. But user reports visual artifacts UNCHANGED.
  This means either: (a) the terrain swap doesn't execute at runtime, (b) something overwrites
  swapped data after init, or (c) the diagnostic measures CPU-side Pattern objects while
  GPU atlas or tilemap texture is stale/incorrect.

### Investigation #9: Code path verification (2026-03-14)
- **Traced full code path for skip-intro terrain swap:**
  - `AizPlaneIntroInstance.resetIntroPhaseState()` resets `mainLevelTerrainSwapAttempted=false` ✓
  - `AizPlaneIntroInstance.skipToMainLevelPhase()` checks the flag, calls `applyMainLevelOverlays()` ✓
  - `applyMainLevelOverlays()` applies chunk + pattern overlays, calls `invalidateAllTilemaps()` ✓
  - `applyPatternOverlay()` uses batch mode: writes CPU Pattern objects, then `endBatch()` uploads
    dirty atlas pages to GPU via `glTexSubImage2D` ✓
  - Tilemap rebuild happens on next render frame via `ensureForegroundTilemapData()` dirty flag ✓
  - `PatternLookup` doesn't need updating (overlays reuse existing atlas positions) ✓
  - Static field reset ordering is correct (reset before skip, not after) ✓
  - Level IS loaded before event init runs (Step 18 in profile, after loadLevelData) ✓
  - `SharedLevel.load()` headless test runs full profile including InitLevelEvents ✓
  - `loadOverlayData()` correctly reads entry 26's secondary as MainLevel art ✓
  - `cachedOverlayData` is ROM-intrinsic, safe to cache across calls ✓
- **Pattern.fromSegaFormat():** Packed nibble format (high nibble=left pixel, low nibble=right pixel).
  Matches VDP 4bpp format. Not planar. Decoding appears correct.
- **Tilemap shader encoding/decoding:** Re-verified `writeTileDescriptor()` R/G byte packing matches
  shader extraction (patternIndex 11-bit, palette 2-bit, hFlip, vFlip, priority). Correct.
- **No PLC overwrites found:** PLC 0x0B is applied by both level loading AND `applyMainLevelOverlays()`,
  but it writes zone object art (plants, vines) at specific VRAM positions, not tree canopy patterns.
- **No pattern animation overwrites:** `Sonic3kPatternAnimator` handles FirstTree art for AIZ2 only,
  not AIZ1 canopy patterns.
- **Diagnosis so far:** Every code path checks out. The CPU data is correct after the swap.
  The GPU should be updated via the batch atlas upload. The tilemap should be rebuilt from
  correct chunk data. Yet the visual shows massive red blocks. The disconnect between
  CPU diagnostic and visual output remains unexplained.
- **Next direction:** User suggested investigating VDP hardware transparency behavior. Search
  disassembly for any special handling of tree area tiles, or any mechanism that makes
  non-zero pixels invisible on real hardware.

### Fix #10: Per-frame palette[2][15] dynamic write (2026-03-14)
- **Hypothesis**: The ROM's AIZ1_Resize stage 2 (loc_1A9EC) writes `Normal_palette_line_3+$1E`
  (= palette[2] color 15) EVERY FRAME with a camera-X-dependent color:
  - Default: `$020E` (R=252, G=0, B=36 — bright red)
  - cameraX >= `$2B00`: `$0004` (R=72, G=0, B=0 — nearly black)
  - cameraX >= `$2D80`: `$0C02` (R=36, G=0, B=216 — dark blue-purple)
  The engine loaded Pal_AIZ once at cameraX >= `$1308` (palette[2][15] = `$020E`) and NEVER
  updated it dynamically. At the hollow tree area (cameraX ~`$2D30`, past `$2B00`), real
  hardware uses `$0004` (nearly invisible dark pixel) while the engine keeps `$020E` (bright red).
- **Evidence**:
  - ROM disassembly s3.asm lines 32171-32194: stage 2 unconditionally writes `$020E`, then
    conditionally overwrites at `$2B00` and `$2D80` thresholds
  - Engine `Sonic3kAIZEvents.updateAct1()` had NO per-frame palette writes in the
    `boundariesUnlocked` block — only `resizeMaxYFromX()` for Y boundaries
  - S/H mode is confirmed OFF (`$8C81` = bit 3 clear, s3.asm lines 184, 273, 1520)
  - Trailing tiles test: 48 trailing tiles have pixel-15 but 0 FG refs → NOT the source
- **Changes**: Added `updateStage2PaletteColor(cameraX)` to `Sonic3kAIZEvents.updateAct1()`
  after `resizeMaxYFromX()`. Converts Sega color word → `Palette.Color`, sets `pal.setColor(15, ...)`,
  calls `cachePaletteTexture()` for GPU upload. Guarded by `!isFireTransitionActive()`.
- **Files changed**: `Sonic3kAIZEvents.java`
- **Result**: PENDING visual verification. Explains hollow tree red stripes (cameraX past $2B00).
  Palm tree area (cameraX < $2B00) still has $020E on both ROM and engine — those 2,750 red
  pixels exist on real hardware too.

## Verified Correct (Ruled Out)

- **Initial palette data**: Pal_AIZIntro has NO bright red at initial load
- **Palette offset mapping**: S3K `Normal_palette_line_2` = $FC20 = CRAM line 1 = engine palette[1]
- **loadPaletteFromPalPointers()**: Correctly calculates startLine=1 from ramDest=$FC20
- **Runtime palette swap**: Updates both Java Palette objects AND GPU texture
- **Tilemap shader encoding/decoding**: writeTileDescriptor palette bits match shader extraction
- **FBO clear colors**: No bright red in any glClearColor call
- **Palette texture dimensions**: TotalPaletteLines matches texture height
- **Palette texture upload**: Correct rows via glTexSubImage2D
- **Tree tiles 0x39-0x3C**: Pixel indices 1-8 and 0xA only — NO pixel index 15
- **palette[2][15]** = $020E after swap: ROM-accurate (same on real hardware)
- **PatternDesc bit extraction**: Matches VDP format exactly (tested)
- **Chunk loading**: Identical for S2 and S3K — fromSegaFormat() with big-endian 16-bit words
- **Pattern decompression offset**: Secondary art correctly appended at primaryArtSize offset

## Key Technical Facts

### VDP Register 7 (Backdrop Color) — FIXED
- All three games: $8700 = palette 0, color 0. Engine was using palette 2, color 0.

### VDP Layer Compositing Order — FIX #5 ADDRESSES THIS
Real: BG-low → FG-low → BG-high → FG-high → Sprites
Engine (old): BG(all) → FG-low → FG-high → Sprites
Engine (new): BG(all) → FG-low → **BG-high overlay** → FG-high → Sprites

### AIZ1Tree Sprite
Only 16x48 pixels (3 pieces × 16x16, tiles 0x39-0x3C). Terrain filler, not full tree.

### Hollow Tree
Logic-only object, no sprites. Dynamic tilemap editing via AIZ_TreeRevealArray.

## Disproven Hypotheses (this session)
1. Uninitialized pattern 0 in atlas — Pattern 0 IS loaded and is all-zero
2. PatternLookup issues — Correctly built from atlas entries
3. Out-of-range pattern references — `outOfRange=0`
4. Backdrop color — palette 0 color 0 = black
5. Fire curtain rendering — Only active during miniboss transition
6. GPU-specific issues (shader, FBO, GL state) — Root cause is data-level
7. BG high-priority compositing — All AIZ BG tiles are LOW priority
8. Floating-point precision in shader — Verified correct for chunk 0 encoding

## Remaining Low-Priority Red (Post Fix #8)
- 2,750 pixels at `pri=false` in MainLevel art patterns 0x165-0x1DC, chunks 370-459
- All palette 2 color 15 = $020E (red), same as real hardware
- Located in trunk/root area (worldY $02B0-$02D8), behind high-priority canopy tiles
- These exist on real ROM hardware too — the MainLevel art data itself uses pixel index 15
- Visually insignificant: 0.06% of scanned area, all behind canopy
