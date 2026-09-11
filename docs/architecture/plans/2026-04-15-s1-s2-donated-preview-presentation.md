# S1/S2 Donated Preview Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix donated S3K Data Select presentation for S1/S2 by retinting host emerald colors cleanly, using a six-emerald S1 layout, and replacing donated S2 ROM icons with runtime-generated screenshot previews.

**Architecture:** Keep the donated S3K renderer as the rendering owner. Add a small host emerald presentation seam for color/layout decisions, keep S1 runtime preview ownership where it already lives, and mirror that cache/generator/loader stack in Sonic 2 so the donated selected-slot path consumes PNG-backed previews instead of `LevelSelectDataLoader`.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven, Jackson, existing `GameModule`/`DataSelectPresentationProvider`/`S3kDataSelectRenderer` seams, runtime-owned screenshot generation under `saves/image-cache`.

## Final Implementation Notes

- Runtime-generated host previews now render on palette line `2`, leaving palette line `3` free for the dedicated Sonic 2 purple emerald path.
- `S3kDataSelectRenderer` now flushes each layer immediately after batching, which was required to stop later selected-preview palette uploads from rewriting earlier emerald-layer draws.
- Sonic 1 uses a reference-aligned six-emerald orbit profile, while Sonic 2 and S3K keep the native seven-emerald layout.
- Emerald colour donation now preserves native S3K palette ownership and only adds a separate purple palette path where Sonic 2 needs it.

---

## File Map

### Existing files to modify

- `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPaletteBuilder.java`
  Current host emerald palette adapter. Extend or reshape it into a host emerald presentation helper that produces save-card-ready emerald colors via retinting instead of raw slot donation.
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
  Current donated host-preview loader/wiring. Replace the S2 `LevelSelectDataLoader` branch with the runtime cache path and ask the new host emerald presentation seam for both palette bytes and layout metadata.
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
  Current donated renderer. Teach it to render host-specific emerald positions, with S1 using a six-point ring.
- `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
  Mirror the S1 image-cache ownership pattern so S2 owns a lazily-created donated preview cache manager service.
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java`
  Keep `ZONE n` preview text fallback and selected-slot preview index behavior aligned with the new runtime screenshot path.
- `src/main/java/com/openggf/configuration/SonicConfiguration.java`
  Add the S2 screenshot-generation override config key.
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
  Register defaults for the new S2 screenshot-generation config key.
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
  Main integration surface for donated Data Select behavior and renderer-facing asset wiring.
- `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`
  Main host-profile test surface for S2 fallback preview labels and icon index behavior.
- `src/test/java/com/openggf/tests/TestSonicConfigurationService.java`
  Extend config default coverage for the new S2 cache config keys.
- `src/test/java/com/openggf/TestEngine.java`
  Extend module-level warmup/service tests to cover S2 image-cache service ownership and donated-only trigger behavior.

### New files to create

- `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPresentation.java`
  Focused host emerald presenter that returns retinted save-card emerald palette bytes plus layout metadata by host game.
- `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldLayoutProfile.java`
  Small value object or utility holding host-specific emerald orbit positions, including a six-point S1 ring.
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageManifest.java`
  Runtime cache manifest model for S2 PNG previews.
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageGenerator.java`
  Runtime screenshot generator for the 11 S2 donated restart destinations.
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageCacheManager.java`
  Cache validation, donated-only async generation, PNG loading, and manifest handling for S2 previews.
- `src/main/java/com/openggf/game/sonic2/dataselect/S2SelectedSlotPreviewLoader.java`
  PNG-to-pattern/palette/mapping-frame adapter for the donated selected-slot icon seam.
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java`
  Unit tests for hue/saturation retinting, fallback behavior, and S1/S2 emerald slot counts.
- `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectImageCacheManager.java`
  Unit tests for manifest validation, override invalidation, load behavior, and capture target resolution.

## Task 1: Host Emerald Retinting

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPaletteBuilder.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing emerald retint tests**

```java
@Test
void s1HostEmeraldsRetintNativeRampInsteadOfCopyingRawSlots() throws Exception {
    Rom rom = TestRomFactory.sonic1Rom();

    HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", rom);

    assertEquals(6, result.activeEmeraldCount());
    assertEquals(7 * 4 + 2, result.paletteBytes().length);
    assertTrue(result.usesRetintedRamp());
}

@Test
void invalidHostRomFallsBackToEmptyPaletteBytes() {
    HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", null);

    assertEquals(0, result.paletteBytes().length);
    assertEquals(7, result.layout().positions().size());
}
```

- [ ] **Step 2: Run the new emerald tests and verify they fail for the expected reason**

Run: `mvn -Dmse=off "-Dtest=TestHostEmeraldPresentation" test`

Expected: FAIL because `HostEmeraldPresentation` and the new result contract do not exist yet.

- [ ] **Step 3: Implement the minimal host emerald presentation seam**

```java
public final class HostEmeraldPresentation {
    public static Result forHost(String hostGameCode, Rom hostRom) {
        if (hostRom == null || hostGameCode == null || hostGameCode.isBlank()) {
            return Result.fallback();
        }
        return switch (hostGameCode) {
            case "s1" -> buildS1(hostRom);
            case "s2" -> buildS2(hostRom);
            default -> Result.fallback();
        };
    }

    public record Result(byte[] paletteBytes,
                         HostEmeraldLayoutProfile layout,
                         int activeEmeraldCount,
                         boolean usesRetintedRamp) {
        static Result fallback() {
            return new Result(new byte[0], HostEmeraldLayoutProfile.defaultSeven(), 7, false);
        }
    }
}
```

```java
static byte[] composeRetintedPaletteBytes(List<GenesisColor> hostTargets, List<GenesisColor> nativeRamp) {
    byte[] bytes = new byte[7 * 4 + 2];
    for (int i = 0; i < 7; i++) {
        GenesisColor target = i < hostTargets.size() ? hostTargets.get(i) : nativeRamp.get(i * 2);
        GenesisColor hi = retint(nativeRamp.get(i * 2), target);
        GenesisColor lo = retint(nativeRamp.get(i * 2 + 1), target);
        writeGenesisWord(bytes, i * 4, hi.toGenesisWord());
        writeGenesisWord(bytes, i * 4 + 2, lo.toGenesisWord());
    }
    return bytes;
}
```

- [ ] **Step 4: Wire donated presentation to use the new result**

```java
HostEmeraldPresentation.Result emeraldPresentation =
        HostEmeraldPresentation.forHost(hostGameCode, hostRom);
hostEmeraldPaletteBytes = emeraldPresentation.paletteBytes();
hostEmeraldLayoutProfile = emeraldPresentation.layout();
```

- [ ] **Step 5: Run the focused emerald tests and verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestHostEmeraldPresentation,TestS3kDataSelectPresentation" test`

Expected: PASS for the new host emerald retint tests and existing donated presentation tests that still compile against the new seam.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPresentation.java \
        src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldPaletteBuilder.java \
        src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "fix(dataselect): retint donated host emeralds"
```

## Task 2: Sonic 1 Six-Emerald Layout

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldLayoutProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing S1 emerald layout tests**

```java
@Test
void s1EmeraldLayoutUsesSixBalancedPositions() {
    HostEmeraldLayoutProfile layout = HostEmeraldLayoutProfile.s1SixRing();

    assertEquals(6, layout.activeEmeraldCount());
    assertEquals(6, layout.positions().size());
    assertNotEquals(layout.positions().get(0), layout.positions().get(3));
}

@Test
void donatedRendererUsesHostLayoutForEmeraldOrbit() {
    RecordingRenderer renderer = new RecordingRenderer();
    renderer.draw(assetsWithS1Layout(), objectStateWithSixEmeraldFrames());

    assertEquals(6, renderer.renderedEmeraldPositions().size());
}
```

- [ ] **Step 2: Run the layout tests and verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestHostEmeraldPresentation,TestS3kDataSelectPresentation" test`

Expected: FAIL because there is no host layout profile and the renderer still assumes native S3K emerald placement.

- [ ] **Step 3: Implement the layout profile and renderer hook**

```java
public record HostEmeraldLayoutProfile(List<Point> positions, int activeEmeraldCount) {
    public static HostEmeraldLayoutProfile defaultSeven() { ... }
    public static HostEmeraldLayoutProfile s1SixRing() {
        return new HostEmeraldLayoutProfile(List.of(
                new Point(-24, -18),
                new Point(0, -28),
                new Point(24, -18),
                new Point(24, 14),
                new Point(0, 26),
                new Point(-24, 14)
        ), 6);
    }
}
```

```java
for (int i = 0; i < Math.min(slotState.emeraldMappingFrames().size(), layout.activeEmeraldCount()); i++) {
    Point offset = layout.positions().get(i);
    renderObjectFrameAt(graphics, assets, slotState.emeraldMappingFrames().get(i),
            slotWorldX + offset.x(), slotWorldY + offset.y(), SAVE_SCREEN_OBJECT_BASE_DESC);
}
```

- [ ] **Step 4: Thread the layout through the donated asset contract**

```java
default HostEmeraldLayoutProfile getHostEmeraldLayoutProfile() {
    return HostEmeraldLayoutProfile.defaultSeven();
}
```

```java
@Override
public HostEmeraldLayoutProfile getHostEmeraldLayoutProfile() {
    return hostEmeraldLayoutProfile;
}
```

- [ ] **Step 5: Run the focused layout tests and verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestHostEmeraldPresentation,TestS3kDataSelectPresentation" test`

Expected: PASS with S1 rendering six emerald positions and S2/S3K still using the seven-point default.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/HostEmeraldLayoutProfile.java \
        src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java \
        src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "fix(dataselect): use six-emerald layout for sonic 1"
```

## Task 3: Sonic 2 Runtime Screenshot Cache Infrastructure

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageManifest.java`
- Create: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageGenerator.java`
- Create: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageCacheManager.java`
- Create: `src/main/java/com/openggf/game/sonic2/dataselect/S2SelectedSlotPreviewLoader.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Test: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectImageCacheManager.java`
- Test: `src/test/java/com/openggf/tests/TestSonicConfigurationService.java`
- Test: `src/test/java/com/openggf/TestEngine.java`

- [ ] **Step 1: Write the failing cache/config tests**

```java
@Test
void s2CacheIsInvalidWhenOverrideFlagIsTrue() {
    config.setConfigValue(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, true);
    S2DataSelectImageCacheManager manager = new S2DataSelectImageCacheManager(cacheRoot, config, () -> "sha", mapper);

    assertFalse(manager.cacheValid());
}

@Test
void sonicConfigurationServiceDefinesDefaultsForS2PreviewGeneration() {
    SonicConfigurationService service = SonicConfigurationService.getInstance();

    assertEquals(false, service.getBoolean(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE));
    assertEquals(8, service.getInt(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES));
}
```

- [ ] **Step 2: Run the S2 cache/config tests and verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS2DataSelectImageCacheManager,TestSonicConfigurationService,TestEngine" test`

Expected: FAIL because the S2 cache classes and config keys do not exist yet.

- [ ] **Step 3: Add the S2 config keys and defaults**

```java
CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE,
CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES,
```

```java
putDefault(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, false);
putDefault(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES, 8);
```

- [ ] **Step 4: Implement the S2 cache manager and generator by mirroring S1**

```java
public class S2DataSelectImageCacheManager {
    static final int GENERATOR_FORMAT_VERSION = 1;

    public synchronized void ensureGenerationStarted() { ... }
    public boolean cacheValid() { ... }
    public Map<Integer, RgbaImage> loadCachedPreviews() { ... }
    public void awaitGenerationIfRunning() { ... }
}
```

```java
private static final List<ZoneCaptureSpec> ZONES = List.of(
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_EHZ, "ehz", "ehz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_CPZ, "cpz", "cpz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_ARZ, "arz", "arz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_CNZ, "cnz", "cnz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_HTZ, "htz", "htz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_MCZ, "mcz", "mcz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_OOZ, "ooz", "ooz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_MTZ, "mtz", "mtz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_SCZ, "scz", "scz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_WFZ, "wfz", "wfz.png"),
        new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_DEZ, "dez", "dez.png")
);
```

- [ ] **Step 5: Register S2 cache ownership in `Sonic2GameModule`**

```java
private S2DataSelectImageCacheManager dataSelectImageCacheManager;

private S2DataSelectImageCacheManager getDataSelectImageCacheManager() {
    if (dataSelectImageCacheManager == null) {
        dataSelectImageCacheManager = new WarmupAwareS2DataSelectImageCacheManager(
                Path.of("saves", "image-cache", "s2"),
                GameServices.configuration(),
                this::romSha256,
                new ObjectMapper());
    }
    return dataSelectImageCacheManager;
}
```

- [ ] **Step 6: Run the focused S2 infrastructure tests and verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS2DataSelectImageCacheManager,TestSonicConfigurationService,TestEngine" test`

Expected: PASS with S2 config defaults present, cache validation working, and the S2 module exposing the donated preview cache service.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java \
        src/main/java/com/openggf/configuration/SonicConfigurationService.java \
        src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java \
        src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageManifest.java \
        src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageGenerator.java \
        src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectImageCacheManager.java \
        src/main/java/com/openggf/game/sonic2/dataselect/S2SelectedSlotPreviewLoader.java \
        src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectImageCacheManager.java \
        src/test/java/com/openggf/tests/TestSonicConfigurationService.java \
        src/test/java/com/openggf/TestEngine.java
git commit -m "feat(dataselect): add sonic 2 preview cache infrastructure"
```

## Task 4: Replace Donated Sonic 2 ROM Icons With Runtime Screenshots

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the failing donated S2 preview wiring tests**

```java
@Test
void donatedS2UsesPngBackedSelectedSlotPreviewsInsteadOfLevelSelectIcons() {
    S3kDataSelectPresentation presentation = donatedPresentationForS2HostWithCachedPreview();

    presentation.initialize();

    assertTrue(recordingAssets.usedS2RuntimePreviewLoader());
    assertFalse(recordingAssets.usedLevelSelectDataLoader());
}

@Test
void donatedS2FallsBackToNumberedZoneTextWhenPreviewGenerationFails() {
    HostSlotPreview preview = new S2DataSelectProfile().resolveSlotPreview(Map.of("zone", 3));

    assertEquals(HostSlotPreview.PreviewKind.NUMBERED_ZONE, preview.kind());
    assertEquals(4, preview.zoneDisplayNumber());
}
```

- [ ] **Step 2: Run the donated S2 preview tests and verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation,TestS2DataSelectProfile" test`

Expected: FAIL because the presentation still wires S2 through `LevelSelectDataLoader`.

- [ ] **Step 3: Replace the S2 donated preview branch in `S3kDataSelectPresentation`**

```java
if ("s2".equals(hostGameCode)) {
    S2DataSelectImageCacheManager cacheManager = GameServices.module()
            .getGameService(S2DataSelectImageCacheManager.class);
    if (cacheManager == null) {
        return;
    }
    cacheManager.awaitGenerationIfRunning();
    s2SelectedIconPreviews = new S2SelectedSlotPreviewLoader()
            .loadAll(cacheManager.loadCachedPreviews());
    return;
}
```

```java
if (s2SelectedIconPreviews != null && !s2SelectedIconPreviews.isEmpty()) {
    return s2SelectedIconPreviews.get(iconIndex).patterns();
}
```

- [ ] **Step 4: Keep S2 host preview text on `ZONE n` fallback**

```java
@Override
public HostSlotPreview resolveSlotPreview(Map<String, Object> payload) {
    Object zoneObj = payload.get("zone");
    if (!(zoneObj instanceof Number zone)) {
        return null;
    }
    int zoneId = zone.intValue();
    return zoneId >= 0 && zoneId < CLEAR_RESTARTS.size()
            ? HostSlotPreview.numberedZone(zoneId + 1)
            : null;
}
```

- [ ] **Step 5: Run the focused donated S2 tests and verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation,TestS2DataSelectProfile" test`

Expected: PASS with S2 runtime PNG previews wired into the donated selected-slot seam and text-only fallback preserved for failure cases.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java \
        src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java \
        src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "feat(dataselect): use runtime screenshots for donated sonic 2"
```

## Task 5: End-to-End Verification

**Files:**
- Modify: `src/test/java/com/openggf/TestEngine.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
- Modify: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectImageCacheManager.java`

- [ ] **Step 1: Add the final integration coverage**

```java
@Test
void donatedS2WarmupOnlyStartsWhenS3kDonationIsActive() { ... }

@Test
void donatedS2AwaitsInFlightPreviewGenerationBeforeLoadingSelectedIcon() { ... }

@Test
void hostEmeraldFallbackUsesNativePaletteWhenRetintingFails() { ... }
```

- [ ] **Step 2: Run the focused integration suite**

Run: `mvn -Dmse=off "-Dtest=TestEngine,TestS3kDataSelectPresentation,TestS2DataSelectImageCacheManager,TestHostEmeraldPresentation" test`

Expected: PASS with donated warmup, runtime preview loading, and host emerald fallback behavior covered.

- [ ] **Step 3: Run the broader regression suite**

Run: `mvn -Dmse=off "-Dtest=TestGameLoop,TestEngine,TestS1DataSelectImageCacheManager,TestS2DataSelectImageCacheManager,TestS2DataSelectProfile,TestS3kDataSelectPresentation,TestHostEmeraldPresentation,TestSonicConfigurationService" test`

Expected: PASS with no regressions in title-screen fade, S1 preview generation, S2 host preview behavior, or config defaults.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/TestEngine.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java \
        src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectImageCacheManager.java \
        src/test/java/com/openggf/game/sonic3k/dataselect/TestHostEmeraldPresentation.java
git commit -m "test(dataselect): cover host preview presentation flows"
```

- [ ] **Step 5: Run final verification before handoff**

Run: `mvn -Dmse=off clean test`

Expected: PASS, or if unrelated existing failures remain, capture the exact failing test names before reporting status.

## Self-Review

- Spec coverage:
  - host emerald retinting: Task 1
  - S1 six-emerald layout: Task 2
  - S2 runtime screenshot cache/config/manifest: Task 3
  - replacing donated S2 ROM icons with runtime screenshots: Task 4
  - donated-only warmup/fallback/integration coverage: Task 5
- Placeholder scan: no `TODO`, `TBD`, or deferred “write tests later” steps remain.
- Type consistency:
  - `HostEmeraldPresentation.Result`
  - `HostEmeraldLayoutProfile`
  - `S2DataSelectImageCacheManager`
  - `S2SelectedSlotPreviewLoader`
  are used consistently across tasks.
