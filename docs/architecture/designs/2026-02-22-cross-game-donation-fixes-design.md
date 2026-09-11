# Cross-Game Feature Donation Fixes

**Date:** 2026-02-22
**Branch:** `feature/ai-cross-game-donation`
**Scope:** Two bug fixes for the cross-game donation system

## Background

The cross-game feature donation system (`CrossGameFeatureProvider`) allows loading player sprites, physics (spindash), and SFX from a donor game (S2/S3K) into a base game (S1). Two issues remain:

1. Donated sprites have no underwater palette support
2. Donor SFX plays with the base game's SMPS driver config instead of the donor's

Issue #3 (S3K donor crash) was already resolved.

## Fix 1: Underwater Palette for Donated Sprites

### Problem

The underwater palette texture has rows for donor palette lines (4-7+) but they are uninitialized (zero/black). When the water shader samples a donor sprite's palette line underwater, it gets invalid colors.

### Solution

Derive synthetic underwater colors for donor palette lines by applying the base game's zone-specific normal-to-underwater color ratio.

### Changes

**`RenderContext.java`** - New static method:
- `Palette deriveUnderwaterPalette(Palette donorNormal, Palette normalBase, Palette underwaterBase)`
- For each of 16 color slots: compute per-channel ratio `uw[i] / normal[i]`, apply to donor color
- Handle zero-value colors (transparent slot 0, dark colors) gracefully

**`GraphicsManager.cacheUnderwaterPaletteTexture()`** - Extended:
- After populating base game rows 0-3, iterate over `RenderContext.getDonorContexts()`
- For each donor context, derive underwater palette using base game line 0 normal/underwater
- Write derived palette into donor rows in the underwater texture

**No shader changes** - the shader already samples at `(paletteLine + 0.5) / TotalPaletteLines`.

## Fix 2: SMPS Driver Config for Donor SFX

### Problem

`LWJGLAudioBackend.playSfxSmps()` always uses `requireSmpsConfig()` which returns the base game's `SmpsSequencerConfig`. S1/S2/S3K have fundamentally different configs (tempo mode, volume mode, pointer resolution, envelope commands). Donor SFX plays incorrectly or crashes.

### Solution

Pass the donor's `SmpsSequencerConfig` alongside the donor's `SmpsLoader` and `DacData` during registration, and use it when playing donor SFX.

### Changes

**`AudioManager.java`**:
- `registerDonorLoader()` gains `SmpsSequencerConfig config` parameter
- New field: `Map<String, SmpsSequencerConfig> donorConfigs`
- `playSfx()` donor path retrieves donor config and passes it to backend

**`LWJGLAudioBackend.java`**:
- New overload: `playSfxSmps(AbstractSmpsData, DacData, float pitch, SmpsSequencerConfig config)`
- Uses provided config instead of `requireSmpsConfig()` when config is non-null

**`CrossGameFeatureProvider.initializeDonorAudio()`**:
- Extract `donorProfile.getSequencerConfig()` and pass to `registerDonorLoader()`

## Testing

- Underwater: Visual verification in S1 LZ with S2/S3K donor sprites
- Audio: Play S1 with S2 donor, trigger jump SFX - should sound like S2 jump, not corrupted
