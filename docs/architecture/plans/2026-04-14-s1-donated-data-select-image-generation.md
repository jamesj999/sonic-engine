# S1 Donated Data Select Image Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate Sonic 1 zone preview images at runtime for donated S3K Data Select, cache them under `saves/image-cache/s1/`, and use them for selected-slot previews without shipping ROM-derived assets.

**Architecture:** Keep cache ownership inside the Sonic 1 module by exposing a lazily-created `S1DataSelectImageCacheManager` through the existing `GameModule.getGameService(...)` seam. Start generation only for `host=S1` with active `donor=S3K`, validate the cache on a worker thread, run capture work through a narrow render-thread executor, and reuse the existing S3K selected-slot asset hooks so S1 still renders through one donated Data Select presentation path.

**Tech Stack:** Java 21, JUnit 5, Maven, Jackson, LWJGL/STB PNG IO, existing `GameModule`/`DataSelectPresentationProvider`/`S3kDataSelectPresentation` architecture

---

## File Structure

**Create:**
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageCacheManager.java`
  Orchestrates eligibility, manifest validation, async generation state, and awaited access for donated Data Select.
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageGenerator.java`
  Generates all 7 S1 previews, resolves spawn-vs-override capture points, hashes ROM data, and writes PNGs plus `manifest.json`.
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageManifest.java`
  Jackson-backed manifest model containing `engineVersion`, `generatorFormatVersion`, `romSha256`, `generatedAt`, `settleFrames`, and per-zone file entries.
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewAssetLoader.java`
  Loads cached PNGs from `saves/image-cache/s1/` and converts them into S3K selected-slot preview assets.
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewCodec.java`
  Converts `RgbaImage` data into a 16-color `Palette`, `Pattern[]`, and a single `SpriteMappingFrame` covering the 10x7-tile preview area.
- `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectImageCacheManager.java`
  Unit tests for manifest validation, gating, override flag, settle-frame config, and start/await behavior with fake collaborators.
- `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectPreviewAssetLoader.java`
  Tests PNG decode, quantization, tile packing, and selected-slot mapping-frame construction.

**Modify:**
- `src/main/java/com/openggf/configuration/SonicConfiguration.java`
  Add `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE` and `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES`.
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
  Add defaults for the new config keys.
- `src/main/resources/config.json`
  Add the new config keys with defaults `false` and `8`.
- `src/main/java/com/openggf/graphics/GraphicsManager.java`
  Add a narrow render-thread task executor so worker-thread generation can request capture jobs without touching GL directly.
- `src/main/java/com/openggf/Engine.java`
  Start S1 image generation during S1 bootstrap when S3K donation is active, and pump render-thread tasks at safe points.
- `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
  Lazily construct the cache manager, inject it into `S1DataSelectProfile`, and expose it through `getGameService(...)`.
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java`
  Keep text labels, but return a selected-slot icon index only when a generated S1 preview is available.
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
  Teach donated asset loading to await and load S1 cached previews through the same selected-slot hooks already used by S2.
- `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
  Add tests for image-backed selected-slot icon resolution and text fallback.
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
  Add tests for S1 donated preview asset loading and fallback behavior.
- `src/test/java/com/openggf/TestEngine.java`
  Add tests for the S1 bootstrap warmup hook.

**Reference:**
- `docs/architecture/designs/2026-04-14-s1-donated-data-select-image-generation-design.md`

### Task 1: Add config plumbing and cache-validation tests

**Files:**
- Create: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectImageCacheManager.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/resources/config.json`
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageManifest.java`
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageCacheManager.java`

- [ ] **Step 1: Write failing cache-validation tests**

Add tests that pin the manifest contract, `AppVersion.get()` version source, override invalidation, missing PNG invalidation, and settle-frame config acceptance.

```java
@Test
void cacheIsValidWhenManifestMatchesEngineVersionRomHashAndFiles() throws Exception {
    Path cacheRoot = tempDir.resolve("saves/image-cache/s1");
    Files.createDirectories(cacheRoot);
    Files.writeString(cacheRoot.resolve("manifest.json"), """
            {
              "engineVersion": "%s",
              "generatorFormatVersion": 1,
              "romSha256": "abc123",
              "generatedAt": "2026-04-14T21:00:00Z",
              "settleFrames": 8,
              "zones": [{"zoneCode":"ghz","zoneId":0,"file":"ghz.png"}]
            }
            """.formatted(AppVersion.get()));
    ScreenshotCapture.savePNG(new RgbaImage(80, 56, new int[80 * 56]), cacheRoot.resolve("ghz.png"));

    S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
            cacheRoot,
            configWith(false, 8),
            () -> "abc123",
            new FakeGenerator());

    assertTrue(manager.cacheValid());
}

@Test
void overrideFlagForcesRegenerationEvenWhenManifestMatches() {
    S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
            tempDir.resolve("saves/image-cache/s1"),
            configWith(true, 8),
            () -> "abc123",
            new FakeGenerator());

    assertFalse(manager.cacheValid());
}

@Test
void settleFrameConfigAllowsZero() {
    SonicConfigurationService config = configWith(false, 0);
    S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
            tempDir.resolve("saves/image-cache/s1"),
            config,
            () -> "abc123",
            new FakeGenerator());

    assertEquals(0, manager.settleFrames());
}

private SonicConfigurationService configWith(boolean override, int settleFrames) {
    SonicConfigurationService config = new SonicConfigurationService(tempDir.resolve("config.json").toString());
    config.setConfigValue(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, override);
    config.setConfigValue(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES, settleFrames);
    return config;
}

private static final class FakeGenerator extends S1DataSelectImageGenerator {
    FakeGenerator() {
        super(Path.of("unused"), zoneId -> new RgbaImage(80, 56, new int[80 * 56]), () -> "abc123", 8);
    }
}
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectImageCacheManager" test
```

Expected:

- test compilation fails because the new cache manager and manifest types do not exist yet

- [ ] **Step 3: Add the new config keys**

Extend the configuration enum/defaults/resources with the exact names agreed in the spec.

```java
/**
 * Force regeneration of S1 donated Data Select preview images on the next
 * eligible bootstrap.
 */
CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE,

/**
 * Number of hidden frames to step before capturing an S1 donated preview.
 * Zero is valid and captures immediately.
 */
CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES,
```

```java
putDefault(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, false);
putDefault(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES, 8);
```

```json
"CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE": false,
"CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES": 8,
```

- [ ] **Step 4: Add the minimal manifest and cache-manager implementation**

Use Jackson plus a narrow validator so the tests can call `cacheValid()` and `settleFrames()` without any generation logic yet.

```java
public record S1DataSelectImageManifest(
        String engineVersion,
        int generatorFormatVersion,
        String romSha256,
        Instant generatedAt,
        int settleFrames,
        List<ZoneImageEntry> zones
) {
    public record ZoneImageEntry(String zoneCode, int zoneId, String file) {
    }
}
```

```java
public final class S1DataSelectImageCacheManager {
    static final int GENERATOR_FORMAT_VERSION = 1;

    private final Path cacheRoot;
    private final SonicConfigurationService config;
    private final Supplier<String> romSha256Supplier;
    private final S1DataSelectImageGenerator generator;
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean cacheValid() {
        if (config.getBoolean(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE)) {
            return false;
        }
        S1DataSelectImageManifest manifest = loadManifest();
        if (manifest == null) {
            return false;
        }
        if (!AppVersion.get().equals(manifest.engineVersion())) {
            return false;
        }
        if (manifest.generatorFormatVersion() != GENERATOR_FORMAT_VERSION) {
            return false;
        }
        if (!Objects.equals(romSha256Supplier.get(), manifest.romSha256())) {
            return false;
        }
        return manifest.zones().stream()
                .map(S1DataSelectImageManifest.ZoneImageEntry::file)
                .map(cacheRoot::resolve)
                .allMatch(Files::isRegularFile);
    }

    public int settleFrames() {
        return Math.max(0, config.getInt(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES));
    }
}
```

- [ ] **Step 5: Run the cache-validation tests to verify they pass**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectImageCacheManager" test
```

Expected:

- `TestS1DataSelectImageCacheManager` passes

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/resources/config.json src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageManifest.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageCacheManager.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectImageCacheManager.java
git commit -m "Add S1 data select image cache validation"
```

### Task 2: Add a narrow render-thread executor and bootstrap warmup hook

**Files:**
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/test/java/com/openggf/TestEngine.java`

- [ ] **Step 1: Write failing warmup and executor tests**

Add one unit test for `GraphicsManager` task execution ordering and one `Engine` test proving S1+S3K donation starts generation while plain S1 does nothing.

```java
@Test
void renderThreadTasksRunWhenPumped() throws Exception {
    GraphicsManager graphics = new GraphicsManager();
    CompletableFuture<Integer> future = graphics.submitRenderThreadTask(() -> 42);

    assertFalse(future.isDone());
    graphics.runPendingRenderThreadTasks();
    assertEquals(42, future.join());
}

@Test
void initializeGame_startsS1PreviewWarmupOnlyForS3kDonation() {
    TrackingS1ImageCacheManager cacheManager = new TrackingS1ImageCacheManager();
    Sonic1GameModule module = new Sonic1GameModule() {
        @Override
        public <T> T getGameService(Class<T> type) {
            if (type == S1DataSelectImageCacheManager.class) {
                return type.cast(cacheManager);
            }
            return super.getGameService(type);
        }
    };

    Engine engine = new TestableEngine(module, true);
    engine.initializeGame();

    assertEquals(1, cacheManager.ensureStartedCalls);
}

private static final class TrackingS1ImageCacheManager extends S1DataSelectImageCacheManager {
    int ensureStartedCalls;

    @Override
    public synchronized void ensureGenerationStarted() {
        ensureStartedCalls++;
    }
}

private static final class TestableEngine extends Engine {
    TestableEngine(GameModule module, boolean s3kDonationActive) {
        super(EngineServices.fromLegacySingletonsForBootstrap());
        // Override ROM/module detection and donation state so initializeGame() hits the warmup path deterministically.
    }
}
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run:

```bash
mvn -Dmse=off "-Dtest=TestEngine,TestS1DataSelectImageCacheManager" test
```

Expected:

- the new render-thread executor methods do not exist yet
- the S1 bootstrap warmup test fails because `Engine.initializeGame()` never starts generation

- [ ] **Step 3: Add the render-thread task queue to `GraphicsManager`**

Use a minimal queue plus `CompletableFuture` so worker-thread generation can enqueue capture work and await results safely.

```java
private final Queue<PendingRenderThreadTask<?>> pendingRenderThreadTasks = new ConcurrentLinkedQueue<>();

public <T> CompletableFuture<T> submitRenderThreadTask(Callable<T> callable) {
    CompletableFuture<T> future = new CompletableFuture<>();
    pendingRenderThreadTasks.add(new PendingRenderThreadTask<>(callable, future));
    return future;
}

public void runPendingRenderThreadTasks() {
    PendingRenderThreadTask<?> task;
    while ((task = pendingRenderThreadTasks.poll()) != null) {
        task.run();
    }
}

private record PendingRenderThreadTask<T>(Callable<T> callable, CompletableFuture<T> future) {
    void run() {
        try {
            future.complete(callable.call());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
```

- [ ] **Step 4: Wire S1 warmup into `Engine` and expose the cache manager from `Sonic1GameModule`**

Keep the manager module-owned and reachable through the existing `getGameService(...)` seam. Start warmup only for `GameId.S1` with active `CrossGameFeatureProvider.isS3kDonorActive()`.

```java
private void maybeStartS1DonatedDataSelectImageGeneration(GameModule module) {
    if (module == null || module.getGameId() != GameId.S1 || !CrossGameFeatureProvider.isS3kDonorActive()) {
        return;
    }
    S1DataSelectImageCacheManager manager = module.getGameService(S1DataSelectImageCacheManager.class);
    if (manager != null) {
        manager.ensureGenerationStarted();
    }
}
```

```java
initializeGameplayRuntime(gameplayMode, true);
maybeStartS1DonatedDataSelectImageGeneration(module);
graphicsManager.runPendingRenderThreadTasks();
enterConfiguredStartupMode();
```

```java
if (type == S1DataSelectImageCacheManager.class) {
    return (T) dataSelectImageCacheManager;
}
```

- [ ] **Step 5: Run the warmup tests to verify they pass**

Run:

```bash
mvn -Dmse=off "-Dtest=TestEngine,TestS1DataSelectImageCacheManager" test
```

Expected:

- the render-thread queue test passes
- the S1 bootstrap warmup test passes
- plain S1 / non-S3K donation remains untouched

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/graphics/GraphicsManager.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java src/test/java/com/openggf/TestEngine.java
git commit -m "Start S1 donated preview warmup during bootstrap"
```

### Task 3: Implement batch generation, SHA-256 invalidation, and hidden capture requests

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageGenerator.java`
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageCacheManager.java`
- Modify: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectImageCacheManager.java`

- [ ] **Step 1: Write failing generation tests**

Add tests for whole-batch writes, capture-target resolution, and atomic manifest behavior with fake render-thread capture results.

```java
@Test
void generateAllWritesSevenPngsAndManifestAfterSuccess() throws Exception {
    FakeCaptureSource captureSource = FakeCaptureSource.solidColourImages(320, 224, 7);
    S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
            tempDir.resolve("saves/image-cache/s1"),
            captureSource,
            () -> "romsha",
            0);

    generator.generateAll();

    assertTrue(Files.isRegularFile(tempDir.resolve("saves/image-cache/s1/manifest.json")));
    assertEquals(7, Files.list(tempDir.resolve("saves/image-cache/s1"))
            .filter(path -> path.toString().endsWith(".png"))
            .count());
}

@Test
void captureTargetFallsBackToSpawnWhenOverrideMapIsEmpty() {
    S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
            tempDir.resolve("saves/image-cache/s1"),
            FakeCaptureSource.noop(),
            () -> "romsha",
            8);

    PreviewCapturePoint point = generator.resolveCapturePoint(Sonic1ZoneConstants.ZONE_GHZ);

    assertEquals(0x80, point.centreX());
    assertEquals(0xA8, point.centreY());
}

@Test
void failedZoneLeavesNoManifestBehind() {
    S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
            tempDir.resolve("saves/image-cache/s1"),
            FakeCaptureSource.failOnZone(Sonic1ZoneConstants.ZONE_SYZ),
            () -> "romsha",
            8);

    assertThrows(IOException.class, generator::generateAll);
    assertFalse(Files.exists(tempDir.resolve("saves/image-cache/s1/manifest.json")));
}

private static final class FakeCaptureSource implements S1DataSelectImageGenerator.CaptureSource {
    // Return deterministic 320x224 images for each requested zone, or throw for fail-on-zone tests.
}
```

- [ ] **Step 2: Run the generation tests to verify they fail**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectImageCacheManager" test
```

Expected:

- tests fail because the generator still has no batch-generation logic

- [ ] **Step 3: Implement zone order, SHA-256 hashing, temp-file writes, and capture-point resolution**

Keep zone order aligned with `S1DataSelectProfile.CLEAR_RESTARTS`, keep the override map empty for now, and write all PNGs before the manifest.

```java
private static final List<DataSelectDestination> ZONES = List.of(
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_GHZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_MZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_SYZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_LZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_SLZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_SBZ, 0),
        new DataSelectDestination(Sonic1ZoneConstants.ZONE_FZ, 0)
);

private static final Map<Integer, PreviewCapturePoint> CAPTURE_OVERRIDES = Map.of();

PreviewCapturePoint resolveCapturePoint(int zoneId) {
    PreviewCapturePoint override = CAPTURE_OVERRIDES.get(zoneId);
    if (override != null) {
        return override;
    }
    int[] spawn = zoneRegistry.getStartPosition(zoneId, 0);
    return new PreviewCapturePoint(spawn[0], spawn[1]);
}
```

```java
private String computeRomSha256() throws IOException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] romBytes = GameServices.rom().getRom().getRomBytes();
    return HexFormat.of().formatHex(digest.digest(romBytes));
}
```

```java
Path temp = Files.createTempFile(cacheRoot, zoneCode, ".png.tmp");
ScreenshotCapture.savePNG(previewImage, temp);
Files.move(temp, cacheRoot.resolve(zoneCode + ".png"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
```

- [ ] **Step 4: Add `ensureGenerationStarted()` / `awaitGenerationIfRunning()` to the cache manager**

Keep the manager idempotent so repeated calls share one in-flight `CompletableFuture`.

```java
public synchronized void ensureGenerationStarted() {
    if (!eligibleForDonatedS3k() || cacheValid() || inFlight != null) {
        return;
    }
    inFlight = CompletableFuture.runAsync(() -> {
        try {
            generator.generateAll();
            generationSucceeded = true;
        } catch (Exception e) {
            generationFailed = true;
            LOGGER.log(Level.WARNING, "Failed to generate S1 donated data select previews", e);
        }
    }, executor);
}

public void awaitGenerationIfRunning() {
    CompletableFuture<Void> future = inFlight;
    if (future != null) {
        future.join();
    }
}
```

- [ ] **Step 5: Run the generation tests to verify they pass**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectImageCacheManager" test
```

Expected:

- generation writes all PNGs and `manifest.json`
- missing/corrupt/forced-invalid caches trigger regeneration
- `settleFrames=0` stays valid

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageGenerator.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectImageCacheManager.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectImageCacheManager.java
git commit -m "Generate cached S1 donated data select previews"
```

### Task 4: Load cached PNGs into the donated S3K selected-slot preview path

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewCodec.java`
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewAssetLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Create: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectPreviewAssetLoader.java`
- Modify: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write failing loader and presentation tests**

Add tests that prove a cached S1 PNG becomes an 80x56 selected-slot preview, while missing cache keeps the current text-only fallback.

```java
@Test
void loadPreviewAssets_buildsTenBySevenTileSelectedSlotFrame() throws Exception {
    Path cacheRoot = tempDir.resolve("saves/image-cache/s1");
    Files.createDirectories(cacheRoot);
    ScreenshotCapture.savePNG(colourBars(80, 56), cacheRoot.resolve("ghz.png"));

    S1DataSelectPreviewAssetLoader loader = new S1DataSelectPreviewAssetLoader(cacheRoot);
    S1DataSelectPreviewAssetLoader.SelectedSlotPreview preview = loader.loadForZone(0);

    assertEquals(70, preview.patterns().length);
    assertNotNull(preview.palette());
    assertEquals(70, preview.frame().pieces().size());
}

@Test
void resolveSelectedSlotIconIndex_returnsMinusOneWhenNoGeneratedPreviewExists() {
    S1DataSelectProfile profile = new S1DataSelectProfile(zoneId -> false);

    assertEquals(-1, profile.resolveSelectedSlotIconIndex(Map.of("zone", 0), null));
}

@Test
void donatedPresentation_usesS1CachedPreviewWhenAvailable() throws Exception {
    SaveManager saveManager = new SaveManager(root);
    saveManager.writeSlot("s1", 1, Map.of(
            "zone", 0,
            "act", 0,
            "mainCharacter", "sonic",
            "sidekicks", List.of(),
            "lives", 3,
            "clear", false
    ));
    RecordingAssets assets = new RecordingAssets(0x2A);
    assets.installS1Preview(0);
    RecordingRenderer renderer = new RecordingRenderer();
    DataSelectSessionController controller = new DataSelectSessionController(new S1DataSelectProfile(zoneId -> true));

    S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
            controller,
            saveManager,
            RuntimeManager.currentEngineServices().configuration(),
            assets,
            renderer,
            ignored -> {
            });
    presentation.initialize();
    presentation.draw();

    assertNotNull(renderer.lastObjectState.selectedSlotIcon());
    assertFalse(renderer.lastObjectState.selectedSlotIcon().finishCard());
    assertEquals(0, renderer.lastObjectState.selectedSlotIcon().iconIndex());
}

private RgbaImage colourBars(int width, int height) {
    int[] pixels = new int[width * height];
    Arrays.fill(pixels, 0xFFFFAA00);
    return new RgbaImage(width, height, pixels);
}
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectPreviewAssetLoader,TestS1DataSelectProfile,TestS3kDataSelectPresentation" test
```

Expected:

- the S1 PNG preview loader types do not exist yet
- `S1DataSelectProfile` still returns `-1` for selected-slot previews
- the presentation has no S1 host-preview asset branch

- [ ] **Step 3: Implement PNG-to-pattern conversion**

Target the existing S3K selected-slot dimensions exactly: `80x56`, or `10x7` patterns. Use a 16-color palette with index `0` reserved for transparency.

```java
public SelectedSlotPreview loadForZone(int zoneId) throws IOException {
    RgbaImage png = ScreenshotCapture.loadPNG(resolveZoneFile(zoneId));
    if (png.width() != 80 || png.height() != 56) {
        throw new IOException("Expected 80x56 preview PNG for zone " + zoneId);
    }
    return S1DataSelectPreviewCodec.encode(png);
}
```

```java
public static SelectedSlotPreview encode(RgbaImage image) {
    Palette palette = quantizePalette(image);
    Pattern[] patterns = encodePatterns(image, palette);
    SpriteMappingFrame frame = buildFrame(patterns.length, -40, -120);
    return new SelectedSlotPreview(patterns, palette, frame);
}
```

- [ ] **Step 4: Switch `S1DataSelectProfile` and `S3kDataSelectPresentation` to the new cache-backed path**

Keep the slot label text-only. Only selected-slot imagery becomes image-backed.

```java
@Override
public int resolveSelectedSlotIconIndex(Map<String, Object> payload, DataSelectDestination clearDestination) {
    Object zone = payload != null ? payload.get("zone") : null;
    int zoneId = clearDestination != null
            ? clearDestination.zone()
            : zone instanceof Number number ? number.intValue() : -1;
    return zoneId >= 0 && hasGeneratedPreview.test(zoneId) ? zoneId : -1;
}
```

```java
private void loadHostPreviewAssets() {
    if ("s2".equals(hostGameCode)) {
        loadS2PreviewAssets();
        return;
    }
    if ("s1".equals(hostGameCode)) {
        S1DataSelectImageCacheManager manager = GameServices.module()
                .getGameService(S1DataSelectImageCacheManager.class);
        if (manager == null) {
            return;
        }
        manager.awaitGenerationIfRunning();
        s1PreviewLoader = manager.openPreviewAssetLoader();
    }
}
```

```java
public S1DataSelectPreviewAssetLoader openPreviewAssetLoader() {
    return cacheValid() ? new S1DataSelectPreviewAssetLoader(cacheRoot) : null;
}
```

```java
@Override
public Pattern[] getSlotIconPatterns(int iconIndex) {
    if (s1PreviewLoader != null) {
        return s1PreviewLoader.loadForZone(iconIndex).patterns();
    }
    if (s2PreviewLoader != null) {
        return s2SlotIconPatterns(iconIndex);
    }
    return loader != null ? loader.getSlotIconPatterns(iconIndex) : new Pattern[0];
}
```

- [ ] **Step 5: Run the selected-slot preview tests to verify they pass**

Run:

```bash
mvn -Dmse=off "-Dtest=TestS1DataSelectPreviewAssetLoader,TestS1DataSelectProfile,TestS3kDataSelectPresentation" test
```

Expected:

- S1 cached PNGs become selected-slot preview assets
- S1 text labels remain intact
- missing or failed cache keeps `-1` and the current text-only fallback

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewCodec.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectPreviewAssetLoader.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectPreviewAssetLoader.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "Render S1 cached previews on donated S3K data select"
```

### Task 5: Final verification and manual preview capture check

**Files:**
- Modify: only if verification exposes defects

- [ ] **Step 1: Run the focused regression slice**

Run:

```bash
mvn -Dmse=off "-Dtest=TestEngine,TestS1DataSelectImageCacheManager,TestS1DataSelectPreviewAssetLoader,TestS1DataSelectProfile,TestS3kDataSelectPresentation" test
```

Expected:

- all S1 donated image-cache, warmup, and presentation tests pass

- [ ] **Step 2: Run the full suite**

Run:

```bash
mvn -Dmse=off test
```

Expected:

- full suite passes, or any unrelated existing failures are called out explicitly before merge

- [ ] **Step 3: Manually verify the runtime flow**

Run the game with donated S1-on-S3K configuration:

```bash
java -jar target/OpenGGF-*-jar-with-dependencies.jar
```

Manual checks:

- delete `saves/image-cache/s1/` before boot
- enable `CROSS_GAME_FEATURES_ENABLED=true` and `CROSS_GAME_SOURCE=s3k`
- boot Sonic 1 through the master title
- confirm `saves/image-cache/s1/manifest.json` plus 7 PNGs are created
- enter donated Data Select and confirm selected slots show zone imagery
- set `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_SETTLE_FRAMES=0` and inspect for transient artifacts
- set `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE=true` and confirm cache regeneration happens on next eligible bootstrap

- [ ] **Step 4: Push the branch**

```bash
git push origin feature/ai-s3k-data-select-save
```
