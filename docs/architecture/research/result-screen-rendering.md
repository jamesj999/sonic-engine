# Results Screen Rendering Bug - Troubleshooting Documentation

**Status: RESOLVED**

## Problem Description

The Results screen at the end of the level had several rendering issues:
1. "O" and "N" in "SONIC GOT THROUGH" were not rendering properly
2. "S" from the smaller UI text ("BONUS") was corrupted
3. TIME and RING text was garbage
4. Garbage patterns at the end of score number tallies (should be "0")
5. When bonuses counted down to 0, the "0" disappeared instead of remaining visible

## Root Cause

**The VDP pattern descriptor format uses only 11 bits for pattern indices (0-2047).**

When the Results renderer was registered last in `ObjectRenderManager`, it received a high `basePatternIndex` (~1836). Combined with its internal tile offsets (up to ~465), the total pattern indices exceeded 2047 and were truncated by the 11-bit mask in `PatternDesc`.

### Technical Details

```
Pattern index calculation: basePatternIndex + piece.tileIndex() + tileOffset
Results basePatternIndex: 1836
Tile index for 'N': 100 (from $584 - $520)
Total: 1836 + 100 = 1936 (OK, under 2048)

But with 466 patterns, max index = 1836 + 465 = 2301 (OVERFLOW!)
When masked: 2301 & 0x7FF = 253 (wrong texture!)
```

## Solution Applied

### Fix 1: Dedicated Pattern Namespace for Results

Results renderer is NOT registered in the normal `sheetOrder`. Instead, it's cached separately with `basePatternIndex=0x10000` (65536), completely isolated from other renderers.

**File: `ObjectRenderManager.java`**
```java
// Results screen - NOT registered in sheetOrder, uses separate caching
this.resultsRenderer = new PatternSpriteRenderer(resultsSheet);
// Don't register - Results gets its own pattern namespace in ensurePatternsCached

// In ensurePatternsCached():
resultsRenderer.ensurePatternsCached(graphicsManager, 0x10000);
```

### Fix 2: Full Pattern Index for Texture Lookup

Added `renderPatternWithId()` method that uses the full pattern ID for texture lookup instead of the 11-bit masked value.

**File: `GraphicsManager.java`**
```java
public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
    Integer patternTextureId = patternTextureMap.get("pattern_" + patternId);
    // ... uses patternId instead of desc.getPatternIndex()
}
```

**File: `PatternSpriteRenderer.java`**
- `drawFramePieces()` - Uses `renderPatternWithId(patternIndex, desc, ...)`
- `drawPatternIndex()` - Uses `renderPatternWithId(fullPatternId, desc, ...)`

### Fix 3: Extended VRAM Array

**File: `Sonic2ObjectArt.java`**
```java
// Include space for trailing blank at $6F0-$6F1 (used in results mappings)
maxEnd = Math.max(maxEnd, 0x6F2);
```

### Fix 4: Zero Display Bug

**File: `ResultsScreenObjectInstance.java`**
```java
// Always show the last digit (ones place), even if value is 0
boolean isLastDigit = (i == divisors.length - 1);
if (digit != 0 || hasDigit || isLastDigit) {
    copyDigit(dest, tileIndex, digit, digits);
}
```

## Why This Works

1. **Results isolation** - With `basePatternIndex=0x10000`, Results tile indices (0-465) map to texture IDs 65536-66001, completely separate from other renderers
2. **No 11-bit truncation for Results** - 65636 & 0x7FF = 100, but texture lookup uses the full 65636
3. **Other renderers benefit from renderPatternWithId** - If they exceed 2048, the full index is still used for texture lookup
4. **No registration order dependencies** - The fix doesn't depend on which renderer is registered first

## Files Modified

| File | Changes |
|------|---------|
| `ObjectRenderManager.java` | Results uses separate namespace (0x10000) |
| `GraphicsManager.java` | Added `renderPatternWithId()` method |
| `PatternSpriteRenderer.java` | Both render methods use full pattern ID |
| `Sonic2ObjectArt.java` | Extended VRAM array to 0x6F2 |
| `ResultsScreenObjectInstance.java` | Fixed zero display bug |

## Potential Issues

### 1. Memory Usage
Using `basePatternIndex=0x10000` means texture IDs 65536-66001 are used. This is well within integer range but creates a sparse texture ID space. OpenGL texture IDs are generated separately, so this shouldn't cause issues.

### 2. Future Renderers
Any new renderer with high tile offsets may need similar treatment. Consider:
- Monitoring total pattern count across all renderers
- Adding assertions if `basePatternIndex + maxTileIndex > 2047` for critical renderers

### 3. Pattern Cache Size
The `patternTextureMap` in `GraphicsManager` now holds keys up to "pattern_66001". HashMap handles sparse keys efficiently, so this shouldn't be a problem.

## Follow-Up Tasks

1. **Add unit tests** - Create tests to verify pattern allocation doesn't exceed limits
2. **Consider refactoring** - The 11-bit limit could be removed entirely from the rendering path since it's only needed for VDP accuracy in `PatternDesc` flags
3. **Document pattern allocation** - Add comments explaining the pattern namespace strategy for future maintainers
4. **Title Card screen** - Verify the Title Card (level intro) screen works correctly, as it uses similar mappings

## Related Files (Reference)

- `docs/s2disasm/s2.asm` line 28602: `MapUnc_EOLTitleCards` mappings
- `docs/s2disasm/s2.asm` line 89068: `PlrList_Results` pattern load order
- `docs/s2disasm/s2.constants.asm` line 2562-2592: VRAM tile base constants

## Test File

`src/test/java/uk/co/jamesj999/sonic/tests/TestResultsVramLayout.java` - Verifies VRAM layout and pattern loading (can be removed or kept for regression testing).
