# Cross-Game Donation Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix two cross-game donation bugs: (1) donated sprites have no underwater palette, (2) donor SFX plays with wrong SMPS driver config.

**Architecture:** Underwater fix derives synthetic donor water palettes from the base game's color shift ratio. Audio fix threads the donor's `SmpsSequencerConfig` through registration and playback so each SFX plays with its correct driver semantics.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, OpenGL (palette textures), SMPS audio driver

---

### Task 1: Add `getDonorContexts()` to RenderContext

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/graphics/RenderContext.java`

**Step 1: Add static accessor for donor contexts**

Add after the `reset()` method (after line 68):

```java
/**
 * Returns all active donor render contexts (unmodifiable).
 */
public static java.util.Collection<RenderContext> getDonorContexts() {
    return java.util.Collections.unmodifiableCollection(donorContexts.values());
}
```

**Step 2: Run existing tests**

Run: `mvn test -Dtest=TestRenderContext -q`
Expected: All 7 tests PASS (no behavior change)

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/graphics/RenderContext.java
git commit -m "feat: expose getDonorContexts() on RenderContext"
```

---

### Task 2: Add `deriveUnderwaterPalette()` to RenderContext — test first

**Files:**
- Modify: `src/test/java/uk/co/jamesj999/sonic/graphics/TestRenderContext.java`
- Modify: `src/main/java/uk/co/jamesj999/sonic/graphics/RenderContext.java`

**Step 1: Write the failing test**

Add to `TestRenderContext.java`:

```java
@Test
public void deriveUnderwaterPalette_appliesColorShiftRatio() {
    // Base game normal palette: color 1 = (200, 100, 50)
    uk.co.jamesj999.sonic.level.Palette normalBase = new uk.co.jamesj999.sonic.level.Palette();
    normalBase.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 200, (byte) 100, (byte) 50));

    // Base game underwater palette: color 1 = (100, 50, 25) — half brightness
    uk.co.jamesj999.sonic.level.Palette underwaterBase = new uk.co.jamesj999.sonic.level.Palette();
    underwaterBase.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 100, (byte) 50, (byte) 25));

    // Donor palette: color 1 = (180, 80, 40)
    uk.co.jamesj999.sonic.level.Palette donorNormal = new uk.co.jamesj999.sonic.level.Palette();
    donorNormal.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 180, (byte) 80, (byte) 40));

    uk.co.jamesj999.sonic.level.Palette result = RenderContext.deriveUnderwaterPalette(
            donorNormal, normalBase, underwaterBase);

    // Expected: donor * (underwater/normal) = (180*100/200, 80*50/100, 40*25/50) = (90, 40, 20)
    uk.co.jamesj999.sonic.level.Palette.Color c = result.getColor(1);
    assertEquals(90, Byte.toUnsignedInt(c.r));
    assertEquals(40, Byte.toUnsignedInt(c.g));
    assertEquals(20, Byte.toUnsignedInt(c.b));
}

@Test
public void deriveUnderwaterPalette_handlesZeroBaseColor() {
    // Base normal has zero (black) at color 2
    uk.co.jamesj999.sonic.level.Palette normalBase = new uk.co.jamesj999.sonic.level.Palette();
    normalBase.setColor(2, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 0, (byte) 0, (byte) 0));

    uk.co.jamesj999.sonic.level.Palette underwaterBase = new uk.co.jamesj999.sonic.level.Palette();
    underwaterBase.setColor(2, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 30, (byte) 30, (byte) 30));

    uk.co.jamesj999.sonic.level.Palette donorNormal = new uk.co.jamesj999.sonic.level.Palette();
    donorNormal.setColor(2, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 120, (byte) 60, (byte) 30));

    uk.co.jamesj999.sonic.level.Palette result = RenderContext.deriveUnderwaterPalette(
            donorNormal, normalBase, underwaterBase);

    // When base normal is zero, fallback: use the underwater base color directly
    uk.co.jamesj999.sonic.level.Palette.Color c = result.getColor(2);
    assertEquals(30, Byte.toUnsignedInt(c.r));
    assertEquals(30, Byte.toUnsignedInt(c.g));
    assertEquals(30, Byte.toUnsignedInt(c.b));
}

@Test
public void deriveUnderwaterPalette_clampsTo255() {
    // Base ratio > 1 (underwater is brighter than normal in some channel)
    uk.co.jamesj999.sonic.level.Palette normalBase = new uk.co.jamesj999.sonic.level.Palette();
    normalBase.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 50, (byte) 50, (byte) 50));

    uk.co.jamesj999.sonic.level.Palette underwaterBase = new uk.co.jamesj999.sonic.level.Palette();
    underwaterBase.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 200, (byte) 200, (byte) 200));

    uk.co.jamesj999.sonic.level.Palette donorNormal = new uk.co.jamesj999.sonic.level.Palette();
    donorNormal.setColor(1, new uk.co.jamesj999.sonic.level.Palette.Color(
            (byte) 200, (byte) 200, (byte) 200));

    uk.co.jamesj999.sonic.level.Palette result = RenderContext.deriveUnderwaterPalette(
            donorNormal, normalBase, underwaterBase);

    // 200 * 200/50 = 800, clamped to 255
    uk.co.jamesj999.sonic.level.Palette.Color c = result.getColor(1);
    assertEquals(255, Byte.toUnsignedInt(c.r));
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestRenderContext -q`
Expected: FAIL — `deriveUnderwaterPalette` method does not exist

**Step 3: Implement `deriveUnderwaterPalette()`**

Add to `RenderContext.java` after the `getDonorContexts()` method:

```java
/**
 * Derives an underwater palette for a donor sprite by applying the base
 * game's normal-to-underwater color ratio to each donor color.
 *
 * @param donorNormal    the donor's normal (above-water) palette
 * @param normalBase     the base game's normal palette (line 0)
 * @param underwaterBase the base game's underwater palette (line 0)
 * @return a new Palette with underwater-tinted donor colors
 */
public static Palette deriveUnderwaterPalette(Palette donorNormal,
                                               Palette normalBase,
                                               Palette underwaterBase) {
    Palette result = new Palette();
    for (int i = 0; i < 16; i++) {
        Palette.Color dn = donorNormal.getColor(i);
        Palette.Color nb = normalBase.getColor(i);
        Palette.Color ub = underwaterBase.getColor(i);

        int dnR = Byte.toUnsignedInt(dn.r);
        int dnG = Byte.toUnsignedInt(dn.g);
        int dnB = Byte.toUnsignedInt(dn.b);

        int nbR = Byte.toUnsignedInt(nb.r);
        int nbG = Byte.toUnsignedInt(nb.g);
        int nbB = Byte.toUnsignedInt(nb.b);

        int ubR = Byte.toUnsignedInt(ub.r);
        int ubG = Byte.toUnsignedInt(ub.g);
        int ubB = Byte.toUnsignedInt(ub.b);

        int r, g, b;
        if (nbR > 0) { r = Math.min(255, dnR * ubR / nbR); } else { r = ubR; }
        if (nbG > 0) { g = Math.min(255, dnG * ubG / nbG); } else { g = ubG; }
        if (nbB > 0) { b = Math.min(255, dnB * ubB / nbB); } else { b = ubB; }

        result.setColor(i, new Palette.Color((byte) r, (byte) g, (byte) b));
    }
    return result;
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestRenderContext -q`
Expected: All 10 tests PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/graphics/RenderContext.java \
        src/test/java/uk/co/jamesj999/sonic/graphics/TestRenderContext.java
git commit -m "feat: add deriveUnderwaterPalette to RenderContext with tests"
```

---

### Task 3: Populate donor rows in underwater palette texture

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/graphics/GraphicsManager.java` (method `cacheUnderwaterPaletteTexture`, ~line 788)

**Step 1: Read `cacheUnderwaterPaletteTexture` for exact context**

Read `GraphicsManager.java` lines 785-845 to see the current loop structure and buffer layout.

**Step 2: Add donor palette population**

After the existing loop that populates rows 0 through `totalLines-1` (around line 833, just before `paletteBuffer.flip()`), the current code already iterates all `totalLines` rows. The fix is in how it handles rows beyond `palettes.length`.

Currently, rows >= `palettes.length` get all-zeros (black). The change: for rows that belong to a donor context, derive underwater colors from the donor's normal palette and the base game's shift.

Inside the existing loop body, replace the `null` palette case. Where the code currently checks `(palettes != null && pIndex < palettes.length) ? palettes[pIndex] : null`, add logic to look up donor palettes:

```java
for (int pIndex = 0; pIndex < totalLines; pIndex++) {
    Palette p = (palettes != null && pIndex < palettes.length) ? palettes[pIndex] : null;

    // For donor palette lines, derive underwater palette from base game's color shift
    if (p == null && palettes != null && palettes.length > 0) {
        Palette baseLine0 = cachePaletteForLine(0); // base game normal line 0
        Palette uwLine0 = (palettes.length > 0) ? palettes[0] : null;
        if (baseLine0 != null && uwLine0 != null) {
            for (RenderContext ctx : RenderContext.getDonorContexts()) {
                int base = ctx.getPaletteLineBase();
                int logicalLine = pIndex - base;
                if (logicalLine >= 0 && logicalLine < RenderContext.LINES_PER_CONTEXT) {
                    Palette donorNormal = ctx.getPalette(logicalLine);
                    if (donorNormal != null) {
                        p = RenderContext.deriveUnderwaterPalette(donorNormal, baseLine0, uwLine0);
                    }
                    break;
                }
            }
        }
    }

    // ... rest of existing color loop unchanged
}
```

**Important:** We need access to the base game's **normal** palette line 0 (not underwater). The existing method only receives underwater palettes. We'll need to read the current palette texture or accept the normal palette as a parameter.

**Approach:** Add a second parameter `Palette normalLine0` to `cacheUnderwaterPaletteTexture()`, or read line 0 from the already-cached palette data. Check where `cacheUnderwaterPaletteTexture` is called and pass the normal palette from there.

Search for call sites of `cacheUnderwaterPaletteTexture` and update them to also pass the base game's normal line 0 palette. The call site in `LevelManager` or `WaterSystem` likely already has access to the normal palette at that point.

**Step 3: Run full test suite**

Run: `mvn test -q`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/graphics/GraphicsManager.java
git commit -m "feat: populate donor underwater palette rows from base game color shift"
```

---

### Task 4: Add `SmpsSequencerConfig` to donor audio registration — test first

**Files:**
- Modify: `src/test/java/uk/co/jamesj999/sonic/audio/TestDonorAudioRouting.java`
- Modify: `src/main/java/uk/co/jamesj999/sonic/audio/AudioManager.java`

**Step 1: Write the failing test**

Add to `TestDonorAudioRouting.java`:

```java
@Test
public void testDonorSfx_UsesProvidedSequencerConfig() {
    Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
    audioManager.setSoundMap(baseMap);

    // Create a distinct config for the donor
    SmpsSequencerConfig donorConfig = SmpsSequencerConfig.builder()
            .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
            .build();

    StubSmpsLoader donorLoader = new StubSmpsLoader();
    donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
    audioManager.registerDonorLoader("s3k", donorLoader, EMPTY_DAC, donorConfig);
    audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s3k", 0xE0);

    audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);

    // Verify the donor config was passed to the backend
    assertNotNull("Donor config should be passed to backend", backend.lastDonorConfig);
    assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW, backend.lastDonorConfig.getTempoMode());
}
```

Update the `RecordingBackend` inner class to capture the config:

```java
private static class RecordingBackend extends NullAudioBackend {
    String lastSfxName;
    String lastFallbackName;
    SmpsSequencerConfig lastDonorConfig;

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {
        lastSfxName = data.toString();
        lastFallbackName = null;
        lastDonorConfig = null;
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch, SmpsSequencerConfig config) {
        lastSfxName = data.toString();
        lastFallbackName = null;
        lastDonorConfig = config;
    }

    @Override
    public void playSfx(String sfxName, float pitch) {
        lastFallbackName = sfxName;
        lastSfxName = null;
        lastDonorConfig = null;
    }
}
```

Also update the existing `registerDonorLoader` calls in tests to use the 3-arg version (no config = null):

Find all existing `audioManager.registerDonorLoader("s2", ...)` calls and keep them as-is — the 3-arg overload should still work (backwards compatible).

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestDonorAudioRouting -q`
Expected: FAIL — `registerDonorLoader(String, SmpsLoader, DacData, SmpsSequencerConfig)` does not exist

**Step 3: Implement the changes**

**AudioManager.java:**

Add new field (after line 26):
```java
private final Map<String, SmpsSequencerConfig> donorConfigs = new HashMap<>();
```

Add 4-arg overload of `registerDonorLoader` (after existing 3-arg version):
```java
public void registerDonorLoader(String gameId, SmpsLoader loader, DacData dacData,
                                SmpsSequencerConfig config) {
    donorLoaders.put(gameId, loader);
    this.donorDacData.put(gameId, dacData);
    if (config != null) {
        donorConfigs.put(gameId, config);
    }
}
```

Update `playSfx()` donor path (~line 155-163) to pass config:
```java
if (binding != null) {
    SmpsLoader loader = donorLoaders.get(binding.gameId());
    DacData dData = donorDacData.get(binding.gameId());
    if (loader != null && dData != null) {
        AbstractSmpsData sfx = loader.loadSfx(binding.sfxId());
        if (sfx != null) {
            SmpsSequencerConfig donorConfig = donorConfigs.get(binding.gameId());
            if (donorConfig != null) {
                backend.playSfxSmps(sfx, dData, pitch, donorConfig);
            } else {
                backend.playSfxSmps(sfx, dData, pitch);
            }
            played = true;
        }
    }
}
```

Update `clearDonorAudio()` to also clear `donorConfigs`:
```java
public void clearDonorAudio() {
    donorLoaders.clear();
    donorDacData.clear();
    donorSoundBindings.clear();
    donorConfigs.clear();
}
```

**AudioBackend.java** — add new method with default:
```java
default void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                         SmpsSequencerConfig config) {
    // Default: ignore config, use existing behavior
    playSfxSmps(data, dacData, pitch);
}
```

**NullAudioBackend.java** — add override:
```java
@Override
public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                         SmpsSequencerConfig config) {
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestDonorAudioRouting -q`
Expected: All 7 tests PASS (6 existing + 1 new)

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/audio/AudioManager.java \
        src/main/java/uk/co/jamesj999/sonic/audio/AudioBackend.java \
        src/main/java/uk/co/jamesj999/sonic/audio/NullAudioBackend.java \
        src/test/java/uk/co/jamesj999/sonic/audio/TestDonorAudioRouting.java
git commit -m "feat: thread donor SmpsSequencerConfig through audio registration and playback"
```

---

### Task 5: Implement `playSfxSmps` config overload in LWJGLAudioBackend

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/audio/LWJGLAudioBackend.java`

**Step 1: Add the config-aware overload**

After the existing `playSfxSmps(data, dacData, pitch)` method (~line 352), add:

```java
@Override
public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                         SmpsSequencerConfig config) {
    if (sfxBlocked) {
        return;
    }

    SmpsSequencerConfig effectiveConfig = (config != null) ? config : requireSmpsConfig();

    // Same logic as playSfxSmps(data, dacData, pitch) but using effectiveConfig
    boolean dacInterpolate = SonicConfigurationService.getInstance()
            .getBoolean(SonicConfiguration.DAC_INTERPOLATE);
    boolean fm6DacOff = SonicConfigurationService.getInstance()
            .getBoolean(SonicConfiguration.FM6_DAC_OFF);

    int sfxPriority = (audioProfile != null) ? audioProfile.getSfxPriority(data.getId()) : 0x70;
    boolean specialSfx = (audioProfile != null) && audioProfile.isSpecialSfx(data.getId());

    if (smpsDriver != null && currentStream == smpsDriver) {
        SmpsSequencer seq = new SmpsSequencer(data, dacData, smpsDriver, effectiveConfig);
        seq.setSampleRate(smpsDriver.getOutputSampleRate());
        seq.setFm6DacOff(fm6DacOff);
        seq.setSfxMode(true);
        seq.setPitch(pitch);
        seq.setSfxPriority(sfxPriority);
        seq.setSpecialSfx(specialSfx);
        if (currentSmps != null) {
            seq.setFallbackVoiceData(currentSmps.getSmpsData());
        }
        smpsDriver.addSequencer(seq, true);
    } else {
        synchronized (streamLock) {
            SmpsDriver sfxDriver;
            if (sfxStream instanceof SmpsDriver) {
                sfxDriver = (SmpsDriver) sfxStream;
            } else {
                sfxDriver = new SmpsDriver(getSmpsOutputRate());
                sfxDriver.setDacInterpolate(dacInterpolate);
                sfxStream = sfxDriver;
            }
            sfxDriver.setOutputSampleRate(getSmpsOutputRate());
            applyPsgNoiseConfig(sfxDriver);
            SmpsSequencer seq = new SmpsSequencer(data, dacData, sfxDriver, effectiveConfig);
            seq.setSampleRate(sfxDriver.getOutputSampleRate());
            seq.setFm6DacOff(fm6DacOff);
            seq.setSfxMode(true);
            seq.setPitch(pitch);
            seq.setSfxPriority(sfxPriority);
            seq.setSpecialSfx(specialSfx);
            if (currentSmps != null) {
                seq.setFallbackVoiceData(currentSmps.getSmpsData());
            }
            sfxDriver.addSequencer(seq, true);
        }
    }

    int queued = alGetSourcei(musicSource, AL_BUFFERS_QUEUED);
    if (queued == 0) {
        alSourceStop(musicSource);
        alSourcei(musicSource, AL_BUFFER, 0);
        startStream();
    }
}
```

**Note:** This duplicates the body of the pitch overload. Consider refactoring the existing `playSfxSmps(data, dacData, pitch)` to delegate to the 4-arg version: `playSfxSmps(data, dacData, pitch, null)`. This avoids code duplication.

**Step 2: Run full test suite**

Run: `mvn test -q`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/audio/LWJGLAudioBackend.java
git commit -m "feat: implement config-aware playSfxSmps in LWJGLAudioBackend"
```

---

### Task 6: Wire donor config in CrossGameFeatureProvider

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/CrossGameFeatureProvider.java` (method `initializeDonorAudio`, ~line 175)

**Step 1: Extract and pass the donor's sequencer config**

In `initializeDonorAudio()`, after creating `donorProfile` and before the try block, extract the config:

```java
SmpsSequencerConfig donorConfig = donorProfile.getSequencerConfig();
```

Then change line 189 from:
```java
am.registerDonorLoader(donorGameId, donorSmpsLoader, donorDacData);
```
to:
```java
am.registerDonorLoader(donorGameId, donorSmpsLoader, donorDacData, donorConfig);
```

**Step 2: Run full test suite**

Run: `mvn test -q`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/CrossGameFeatureProvider.java
git commit -m "feat: pass donor SmpsSequencerConfig during audio initialization"
```

---

### Task 7: Integration test — full build verification

**Step 1: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS, no regressions

**Step 2: Build the JAR**

Run: `mvn package -q`
Expected: BUILD SUCCESS

**Step 3: Final commit if any fixups needed**

If any files needed adjustment, commit with descriptive message.
